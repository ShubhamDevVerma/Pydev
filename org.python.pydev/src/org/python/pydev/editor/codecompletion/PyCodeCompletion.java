/*
 * Created on Aug 11, 2004
 *
 * @author Fabio Zadrozny
 */
package org.python.pydev.editor.codecompletion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.contentassist.CompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.python.pydev.core.ExtensionHelper;
import org.python.pydev.core.FullRepIterable;
import org.python.pydev.core.ICodeCompletionASTManager;
import org.python.pydev.core.ICompletionState;
import org.python.pydev.core.IModule;
import org.python.pydev.core.IPythonNature;
import org.python.pydev.core.IToken;
import org.python.pydev.core.ICodeCompletionASTManager.ImportInfo;
import org.python.pydev.core.docutils.PySelection;
import org.python.pydev.core.log.Log;
import org.python.pydev.core.structure.CompletionRecursionException;
import org.python.pydev.editor.codecompletion.revisited.ASTManager;
import org.python.pydev.editor.codecompletion.revisited.CompletionState;
import org.python.pydev.editor.codecompletion.revisited.modules.AbstractModule;
import org.python.pydev.editor.codecompletion.revisited.modules.CompiledModule;
import org.python.pydev.editor.codecompletion.revisited.visitors.FindScopeVisitor;
import org.python.pydev.editor.codecompletion.revisited.visitors.LocalScope;
import org.python.pydev.editor.codecompletion.shell.AbstractShell;
import org.python.pydev.parser.PyParser;
import org.python.pydev.parser.jython.SimpleNode;
import org.python.pydev.parser.jython.ast.ClassDef;
import org.python.pydev.parser.jython.ast.Name;
import org.python.pydev.parser.jython.ast.NameTok;
import org.python.pydev.parser.visitors.NodeUtils;

/**
 * @author Dmoore
 * @author Fabio Zadrozny
 */
public class PyCodeCompletion extends AbstractPyCodeCompletion {

    
    /**
     * This constant is used to debug the code-completion process on a production environment,
     * so that we gather enough information about what's happening and the possible reasons
     * for some bug (at this moment this is being specifically added because of a halting bug
     * for pydev in linux: https://sourceforge.net/tracker/index.php?func=detail&aid=1509582&group_id=85796&atid=577329)
     */
    public static boolean DEBUG_CODE_COMPLETION = PyCodeCompletionPreferencesPage.isToDebugCodeCompletion();
    
    /* (non-Javadoc)
     * @see org.python.pydev.editor.codecompletion.IPyCodeCompletion#getCodeCompletionProposals(org.eclipse.jface.text.ITextViewer, org.python.pydev.editor.codecompletion.CompletionRequest)
     */
    public List getCodeCompletionProposals(ITextViewer viewer, CompletionRequest request) throws CoreException, BadLocationException {
        if(DEBUG_CODE_COMPLETION){
            Log.toLogFile(this,"Starting getCodeCompletionProposals");
            Log.addLogLevel();
            Log.toLogFile(this,"Request:"+request);
        }
        
        ArrayList<ICompletionProposal> ret = new ArrayList<ICompletionProposal>();
        
        //let's see if we should do a code-completion in the current scope...
        if(!isValidCompletionContext(request)){
            request.showTemplates = false;
            return ret;
        }
        
        try {
        	IPythonNature pythonNature = request.nature;
            checkPythonNature(pythonNature);
            
            ICodeCompletionASTManager astManager = pythonNature.getAstManager();
            if (astManager == null) { 
                //we're probably still loading it.
                return ret;
            }
            
            //list of Object[], IToken or ICompletionProposal
            List<Object> tokensList = new ArrayList<Object>();
            lazyStartShell(request);

            String trimmed = request.activationToken.replace('.', ' ').trim();

            ImportInfo importsTipper = getImportsTipperStr(request);

            int line = request.doc.getLineOfOffset(request.documentOffset);
            IRegion region = request.doc.getLineInformation(line);

            ICompletionState state = new CompletionState(line, request.documentOffset - region.getOffset(), null, request.nature, request.qualifier);
            state.setIsInCalltip(request.isInCalltip);

            boolean importsTip = false;
            
            if (importsTipper.importsTipperStr.length() != 0) {
                //code completion in imports 
                importsTip = doImportCompletion(request, astManager, tokensList, importsTipper);

            } else if (trimmed.equals("") == false && request.activationToken.indexOf('.') != -1) {
                //code completion for a token
                doTokenCompletion(request, astManager, tokensList, trimmed, state);

            } else { 
                //go to globals
                doGlobalsCompletion(request, astManager, tokensList, state);
            }

            Set<String> alreadyChecked = new HashSet<String>();
            
            for(ListIterator it=tokensList.listIterator(); it.hasNext();){
                Object o = it.next();
                if(o instanceof IToken){
                    alreadyChecked.clear();
                    IToken initialToken = (IToken) o;
                    
                    IToken token = initialToken;
                    while(token.isImportFrom()){
                        String strRep = token.toString();
                        if(alreadyChecked.contains(strRep)){
                            break;
                        }
                        alreadyChecked.add(strRep);
                        
                        ICompletionState s = state.getCopyForResolveImportWithActTok(token.getRepresentation());
                        s.checkFindResolveImportMemory(token);
                        
                        IToken token2 = astManager.resolveImport(s, token);
                        if(token2 != null && initialToken != token2){
                            initialToken.setArgs(token2.getArgs());
                            initialToken.setDocStr(token2.getDocStr());
                            token = token2;
                        }
                    }
                }
            }
            changeItokenToCompletionPropostal(viewer, request, ret, tokensList, importsTip, state);
        } catch (CompletionRecursionException e) {
            ret.add(new CompletionProposal("",request.documentOffset,0,0,null,e.getMessage(), null,null));
        }
        
        if(DEBUG_CODE_COMPLETION){
            Log.remLogLevel();
            Log.toLogFile(this, "Finished completion. Returned:"+ret.size()+" completions.\r\n");
        }

        return ret;
    }

    /**
     * @return whether we're currently in a valid context for a code-completion request for this engine.
     */
    private boolean isValidCompletionContext(CompletionRequest request) {
        //this engine does not work 'correctly' in the default scope on: 
        //- class definitions - after 'class' and before '('
        //- method definitions - after 'def' and before '(' 
        PySelection ps = request.getPySelection();
        if(ps.isInDeclarationLine() != PySelection.DECLARATION_NONE){
            return false;
        }
        return true;
    }

    /**
     * Does a code-completion that will retrieve the globals in the module
     */
    private void doGlobalsCompletion(CompletionRequest request, ICodeCompletionASTManager astManager, List<Object> tokensList, ICompletionState state) throws CompletionRecursionException {
        state.setActivationToken(request.activationToken);
        if(DEBUG_CODE_COMPLETION){
            Log.toLogFile(this,"astManager.getCompletionsForToken");
            Log.addLogLevel();
        }
        IToken[] comps = astManager.getCompletionsForToken(request.editorFile, request.doc, state);
        if(DEBUG_CODE_COMPLETION){
            Log.remLogLevel();
            Log.toLogFile(this,"END astManager.getCompletionsForToken");
        }

        tokensList.addAll(Arrays.asList(comps));
        
        tokensList.addAll(getGlobalsFromParticipants(request, state));
    }

    /**
     * Does a code-completion that will retrieve the all matches for some token in the module
     */
    private void doTokenCompletion(CompletionRequest request, ICodeCompletionASTManager astManager, List<Object> tokensList, String trimmed, ICompletionState state) throws CompletionRecursionException {
        if (request.activationToken.endsWith(".")) {
            request.activationToken = request.activationToken.substring(0, request.activationToken.length() - 1);
        }
        
        char[] toks = new char[]{'.', ' '};
        List<Object> completions = new ArrayList<Object>();
        if (trimmed.equals("self") || FullRepIterable.getFirstPart(trimmed, toks).equals("self")) {
            state.setLookingFor(ICompletionState.LOOKING_FOR_INSTANCED_VARIABLE);
            getSelfOrClsCompletions(request, tokensList, state, false);
            
        }else if (trimmed.equals("cls") || FullRepIterable.getFirstPart(trimmed, toks).equals("cls")) { 
            state.setLookingFor(ICompletionState.LOOKING_FOR_CLASSMETHOD_VARIABLE);
            getSelfOrClsCompletions(request, tokensList, state, false);

        } else {

            state.setActivationToken(request.activationToken);

            //Ok, looking for a token in globals.
            IToken[] comps = astManager.getCompletionsForToken(request.editorFile, request.doc, state);
            tokensList.addAll(Arrays.asList(comps));
        }
        tokensList.addAll(completions);
    }

    /**
     * Does a code-completion that will check for imports
     */
    private boolean doImportCompletion(CompletionRequest request, ICodeCompletionASTManager astManager, List<Object> tokensList, ImportInfo importsTipper) throws CompletionRecursionException {
        boolean importsTip;
        //get the project and make the code completion!!
        //so, we want to do a code completion for imports...
        //let's see what we have...

        importsTip = true;
        importsTipper.importsTipperStr = importsTipper.importsTipperStr.trim();
        IToken[] imports = astManager.getCompletionsForImport(importsTipper, request);
        tokensList.addAll(Arrays.asList(imports));
        return importsTip;
    }

    /**
     * Checks if the python nature is valid
     */
    private void checkPythonNature(IPythonNature pythonNature) {
        if (pythonNature == null) {
            throw new RuntimeException("Unable to get python nature.");
        }
    }

    /**
     * Pre-initializes the shell (NOT in a thread, as we may need it shortly, so, no use in putting it into a thread)
     */
    private void lazyStartShell(CompletionRequest request) {
        try {
            if(DEBUG_CODE_COMPLETION){
                Log.toLogFile(this,"AbstractShell.getServerShell");
            }
            if (CompiledModule.COMPILED_MODULES_ENABLED) {
                AbstractShell.getServerShell(request.nature, AbstractShell.COMPLETION_SHELL); //just start it
            }
            if(DEBUG_CODE_COMPLETION){
                Log.toLogFile(this,"END AbstractShell.getServerShell");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @return completions added from contributors
     */
    @SuppressWarnings("unchecked")
    private Collection<Object> getGlobalsFromParticipants(CompletionRequest request, ICompletionState state) {
        ArrayList ret = new ArrayList();
        
        List participants = ExtensionHelper.getParticipants(ExtensionHelper.PYDEV_COMPLETION);
        for (Iterator iter = participants.iterator(); iter.hasNext();) {
            IPyDevCompletionParticipant participant = (IPyDevCompletionParticipant) iter.next();
            ret.addAll(participant.getGlobalCompletions(request, state));
        }
        return ret;
    }

    
    /**
     * @param request this is the request for the completion
     * @param theList OUT - returned completions are added here. (IToken instances)
     * @param getOnlySupers whether we should only get things from super classes (in this case, we won't get things from the current class)
     * @return the same tokens added in theList
     */
    public static IToken[] getSelfOrClsCompletions(CompletionRequest request, List theList, ICompletionState state, boolean getOnlySupers) {
    	IToken[] comps = new IToken[0];
        SimpleNode s = PyParser.reparseDocument(new PyParser.ParserInfo(request.doc, true, request.nature, state.getLine())).o1;
        if(s != null){
            FindScopeVisitor visitor = new FindScopeVisitor(state.getLine(), 0);
            try {
                s.accept(visitor);
                comps = getSelfOrClsCompletions(visitor.scope, request, theList, state, getOnlySupers);
            } catch (Exception e1) {
                e1.printStackTrace();
            }
        }
        return comps;
    }
    
    /**
     * Get self completions when you already have a scope
     */
    @SuppressWarnings("unchecked")
    public static IToken[] getSelfOrClsCompletions(LocalScope scope, CompletionRequest request, List theList, ICompletionState state, boolean getOnlySupers) throws BadLocationException {
    	IToken[] comps = new IToken[0];
        while(scope.scope.size() > 0){
            SimpleNode node = (SimpleNode) scope.scope.pop();
            if(node instanceof ClassDef){
                ClassDef d = (ClassDef) node;
                
                if(getOnlySupers){
                    List gottenComps = new ArrayList();
                    for (int i = 0; i < d.bases.length; i++) {
                        if(d.bases[i] instanceof Name){
                            Name n = (Name) d.bases[i];
	                        state.setActivationToken(n.id);
	        	            IToken[] completions;
							try {
								completions = request.nature.getAstManager().getCompletionsForToken(request.editorFile, request.doc, state);
								gottenComps.addAll(Arrays.asList(completions));
							} catch (CompletionRecursionException e) {
								//ok...
							}
                        }
                    }
                    comps = (IToken[]) gottenComps.toArray(new IToken[0]);
                }else{
                    //ok, get the completions for the class, only thing we have to take care now is that we may 
                    //not have only 'self' for completion, but somthing lile self.foo.
                    //so, let's analyze our activation token to see what should we do.
                    
                    String trimmed = request.activationToken.replace('.', ' ').trim();
                    String[] actTokStrs = trimmed.split(" ");
                    if(actTokStrs.length == 0 || (!actTokStrs[0].equals("self")&& !actTokStrs[0].equals("cls")) ){
                        throw new AssertionError("We need to have at least one token (self or cls) for doing completions in the class.");
                    }
                    
                    if(actTokStrs.length == 1){
                        //ok, it's just really self, let's get on to get the completions
                        state.setActivationToken(NodeUtils.getNameFromNameTok((NameTok) d.name));
        	            try {
							comps = request.nature.getAstManager().getCompletionsForToken(request.editorFile, request.doc, state);
						} catch (CompletionRecursionException e) {
							//ok
						}
        	            
                    }else{
                        //it's not only self, so, first we have to get the definition of the token
                        //the first one is self, so, just discard it, and go on, token by token to know what is the last 
                        //one we are completing (e.g.: self.foo.bar)
                        int line = request.doc.getLineOfOffset(request.documentOffset);
                        IRegion region = request.doc.getLineInformationOfOffset(request.documentOffset);
                        int col =  request.documentOffset - region.getOffset();
                        IModule module = AbstractModule.createModuleFromDoc("", null, request.doc, request.nature, line);
                      
                        ASTManager astMan = ((ASTManager)request.nature.getAstManager());
                        comps = astMan.getAssignCompletions(module, new CompletionState(line, col, request.activationToken, request.nature, request.qualifier));

                    }
                }
	            theList.addAll(Arrays.asList(comps));
            }
        }
        return comps;

    }


}