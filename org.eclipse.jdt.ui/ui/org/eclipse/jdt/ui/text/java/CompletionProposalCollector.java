/*******************************************************************************
 * Copyright (c) 2000, 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ui.text.java;

  
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;

import org.eclipse.swt.graphics.Image;

import org.eclipse.jface.resource.ImageDescriptor;

import org.eclipse.jface.text.Assert;
import org.eclipse.jface.text.contentassist.IContextInformation;

import org.eclipse.jdt.core.CompletionContext;
import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.core.CompletionRequestor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.compiler.IProblem;

import org.eclipse.jdt.internal.corext.template.java.SignatureUtil;
import org.eclipse.jdt.internal.corext.util.TypeFilter;


import org.eclipse.jdt.internal.ui.JavaPlugin;
import org.eclipse.jdt.internal.ui.text.java.AnonymousTypeCompletionProposal;
import org.eclipse.jdt.internal.ui.text.java.AnonymousTypeProposalInfo;
import org.eclipse.jdt.internal.ui.text.java.FieldProposalInfo;
import org.eclipse.jdt.internal.ui.text.java.GetterSetterCompletionProposal;
import org.eclipse.jdt.internal.ui.text.java.JavaCompletionProposal;
import org.eclipse.jdt.internal.ui.text.java.JavaTypeCompletionProposal;
import org.eclipse.jdt.internal.ui.text.java.MethodCompletionProposal;
import org.eclipse.jdt.internal.ui.text.java.MethodProposalInfo;
import org.eclipse.jdt.internal.ui.text.java.OverrideCompletionProposal;
import org.eclipse.jdt.internal.ui.text.java.ProposalContextInformation;
import org.eclipse.jdt.internal.ui.text.java.TypeProposalInfo;
import org.eclipse.jdt.internal.ui.viewsupport.ImageDescriptorRegistry;

/**
 * Java UI implementation of <code>CompletionRequestor</code>. Produces
 * {@link IJavaCompletionProposal}s from the proposal descriptors received via
 * the <code>CompletionRequestor</code> interface.
 * <p>
 * Clients may instantiate or subclass.
 * </p>
 * @since 3.1
 */
public class CompletionProposalCollector extends CompletionRequestor {

	/** Tells whether this class is in debug mode. */
	private static final boolean DEBUG= "true".equalsIgnoreCase(Platform.getDebugOption("org.eclipse.jdt.ui/debug/ResultCollector"));  //$NON-NLS-1$//$NON-NLS-2$

	/** Triggers for method proposals without parameters. Do not modify. */
	protected final static char[] METHOD_TRIGGERS= new char[] { ';', ',', '.', '\t', '[', ' ' };
	/** Triggers for method proposals. Do not modify. */
	protected final static char[] METHOD_WITH_ARGUMENTS_TRIGGERS= new char[] { '(', '-', ' ' };
	/** Triggers for types. Do not modify. */
	protected final static char[] TYPE_TRIGGERS= new char[] { '.', '\t', '[', '(', ' ' };
	/** Triggers for variables. Do not modify. */
	protected final static char[] VAR_TRIGGER= new char[] { '\t', ' ', '=', ';', '.' };
	
	private final CompletionProposalLabelProvider fLabelProvider= new CompletionProposalLabelProvider();
	private final ImageDescriptorRegistry fRegistry= JavaPlugin.getImageDescriptorRegistry();

	private final List fJavaProposals= new ArrayList();
	private final List fKeywords= new ArrayList();
	private final Set fSuggestedMethodNames= new HashSet();
	
	private ICompilationUnit fCompilationUnit;
	private final IJavaProject fJavaProject;
	private int fUserReplacementLength;
	
	private CompletionContext fContext;
	private IProblem fLastProblem;	
	
	/* performance instrumentation */
	private long fStartTime;
	private long fUITime;	
	
	
	/**
	 * Creates a new instance ready to collect proposals. If the passed
	 * <code>ICompilationUnit</code> is not contained in an
	 * {@link IJavaProject}, no javadoc will be available as
	 * {@link org.eclipse.jface.text.contentassist.ICompletionProposal#getAdditionalProposalInfo() additional info}
	 * on the created proposals.
	 * 
	 * @param cu the compilation unit that the result collector will operate on
	 */
	public CompletionProposalCollector(ICompilationUnit cu) {
		this(cu.getJavaProject());
		fCompilationUnit= cu;
	}
	
	/**
	 * Creates a new instance ready to collect proposals. Note that proposals
	 * for anonymous types and method declarations are not created when using
	 * this constructor, as those need to know the compilation unit that they
	 * are created on. Use
	 * {@link CompletionProposalCollector#CompletionProposalCollector(ICompilationUnit)}
	 * instead to get all proposals.
	 * <p>
	 * If the passed Java project is <code>null</code>, no javadoc will be
	 * available as
	 * {@link org.eclipse.jface.text.contentassist.ICompletionProposal#getAdditionalProposalInfo() additional info}
	 * on the created (e.g. method and type) proposals.
	 * </p>
	 * @param project the project that the result collector will operate on, or
	 *        <code>null</code>
	 */
	public CompletionProposalCollector(IJavaProject project) {
		fJavaProject= project;
		fCompilationUnit= null;

		fUserReplacementLength= -1;
		fLastProblem= null;

		fJavaProposals.clear();
		fKeywords.clear();
		fSuggestedMethodNames.clear();
	}	

	/**
	 * @inheritDoc
	 * <p>
	 * Subclasses may replace, but usually should not need to. Consider
	 * replacing
	 * {@linkplain #createJavaCompletionProposal(CompletionProposal) createJavaCompletionProposal}
	 * instead.
	 * </p>
	 */
	public void accept(CompletionProposal proposal) {
		long start= DEBUG ? System.currentTimeMillis() : 0;
		try {
			if (isFiltered(proposal))
				return;
			
			if (proposal.getKind() == CompletionProposal.POTENTIAL_METHOD_DECLARATION) {
				acceptPotentialMethodDeclaration(proposal);
			} else {
				IJavaCompletionProposal javaProposal= createJavaCompletionProposal(proposal);
				if (javaProposal != null) {
					fJavaProposals.add(javaProposal);
					if (proposal.getKind() == CompletionProposal.KEYWORD)
						fKeywords.add(javaProposal);
				}
			}
		} catch (IllegalArgumentException e) {
			// all signature processing method may throw IAEs
			// https://bugs.eclipse.org/bugs/show_bug.cgi?id=84657
			// don't abort, but log and show all the valid proposals
			JavaPlugin.log(new Status(IStatus.ERROR, JavaPlugin.getPluginId(), IStatus.OK, "Exception when processing proposal for: " + String.valueOf(proposal.getCompletion()), e)); //$NON-NLS-1$
		}
		
		if (DEBUG) fUITime += System.currentTimeMillis() - start;
	}

	/**
	 * @inheritDoc
	 * <p>
	 * Subclasses may extend, but usually should not need to.
	 * </p>
	 * @see #getContext()
	 */
	public void acceptContext(CompletionContext context) {
		fContext= context;
	}

	/**
	 * @inheritDoc
	 * 
	 * Subclasses may extend, but must call the super implementation.
	 */
	public void beginReporting() {
		if (DEBUG) {
			fStartTime= System.currentTimeMillis();
			fUITime= 0;
		}
	}
	
	/**
	 * @inheritDoc
	 * 
	 * Subclasses may extend, but must call the super implementation.
	 */
	public void completionFailure(IProblem problem) {
		fLastProblem= problem;
	}
	
	/**
	 * @inheritDoc
	 * 
	 * Subclasses may extend, but must call the super implementation.
	 */
	public void endReporting() {
		if (DEBUG) {
			long total= System.currentTimeMillis() - fStartTime;
			System.err.println("Code Assist (core): " + (total - fUITime)); //$NON-NLS-1$
			System.err.println("Code Assist (UI): " + fUITime); //$NON-NLS-1$
		}
		fContext= null;
	}
	
	/**
	 * Returns an error message about any error that may have occurred during
	 * code completion, or the empty string if none.
	 * <p>
	 * Subclasses may replace or extend.
	 * </p>
	 * @return an error message or the empty string
	 */
	public String getErrorMessage() {
		if (fLastProblem != null)
			return fLastProblem.getMessage();
		return ""; //$NON-NLS-1$
	}

	/**
	 * Returns the unsorted list of received proposals.
	 * 
	 * @return the unsorted list of received proposals
	 */
	public final IJavaCompletionProposal[] getJavaCompletionProposals() {
		return (IJavaCompletionProposal[]) fJavaProposals.toArray(new IJavaCompletionProposal[fJavaProposals.size()]);
	}
	
	/**
	 * Returns the unsorted list of received keyword proposals.
	 * 
	 * @return the unsorted list of received keyword proposals
	 */
	public final IJavaCompletionProposal[] getKeywordCompletionProposals() {
		return (JavaCompletionProposal[]) fKeywords.toArray(new JavaCompletionProposal[fKeywords.size()]);
	}

	/**
	 * If the replacement length is set, it overrides the length returned from
	 * the content assist infrastructure. Use this setting if code assist is
	 * called with a none empty selection.
	 * 
	 * @param length the new replacement length, relative to the code assist
	 *        offset. Must be equal to or greater than zero.
	 */
	public final void setReplacementLength(int length) {
		Assert.isLegal(length >= 0);
		fUserReplacementLength= length;
	}

	/**
	 * Computes the relevance for a given <code>CompletionProposal</code>.
	 * <p>
	 * Subclasses may replace, but usually should not need to.
	 * </p>
	 * @param proposal the proposal to compute the relevance for
	 * @return the relevance for <code>proposal</code>
	 */
	protected int computeRelevance(CompletionProposal proposal) {
		final int baseRelevance= proposal.getRelevance() * 16;
		switch (proposal.getKind()) {
			case CompletionProposal.PACKAGE_REF:
				return baseRelevance + 0;
			case CompletionProposal.LABEL_REF:
				return baseRelevance + 1;
			case CompletionProposal.KEYWORD:
				return baseRelevance + 2;
			case CompletionProposal.TYPE_REF:
			case CompletionProposal.ANONYMOUS_CLASS_DECLARATION:
				return baseRelevance + 3;
			case CompletionProposal.METHOD_REF:
			case CompletionProposal.METHOD_NAME_REFERENCE:
			case CompletionProposal.METHOD_DECLARATION:
			case CompletionProposal.ANNOTATION_ATTRIBUTE_REF:
				return baseRelevance + 4;
			case CompletionProposal.POTENTIAL_METHOD_DECLARATION:
				return baseRelevance + 4 /* + 99 */;
			case CompletionProposal.FIELD_REF:
				return baseRelevance + 5;
			case CompletionProposal.LOCAL_VARIABLE_REF:
			case CompletionProposal.VARIABLE_DECLARATION:
				return baseRelevance + 6;
			default:
				return baseRelevance;
		}
	}
	
	/**
	 * Creates a new java completion proposal from a core proposal. This may
	 * involve computing the display label and setting up some context.
	 * <p>
	 * This method is called for every proposal that will be displayed to the
	 * user, which may be hundreds. Implementations should therefore defer as
	 * much work as possible: Labels should be computed lazily to leverage
	 * virtual table usage, and any information only needed when
	 * <em>applying</em> a proposal should not be computed yet.
	 * </p>
	 * <p>
	 * Implementations may return <code>null</code> if a proposal should not
	 * be included in the list presented to the user.
	 * </p>
	 * <p>
	 * Subclasses may extend or replace this method.
	 * </p>
	 * 
	 * @param proposal the core completion proposal to create a UI proposal for
	 * @return the created java completion proposal, or <code>null</code> if
	 *         no proposal should be displayed
	 */
	protected IJavaCompletionProposal createJavaCompletionProposal(CompletionProposal proposal) {
		switch (proposal.getKind()) {
			case CompletionProposal.KEYWORD:
				return createKeywordProposal(proposal);
			case CompletionProposal.PACKAGE_REF:
				return createPackageProposal(proposal);
			case CompletionProposal.TYPE_REF:
				return createTypeProposal(proposal);
			case CompletionProposal.FIELD_REF:
				return createFieldProposal(proposal);
			case CompletionProposal.METHOD_REF:
			case CompletionProposal.METHOD_NAME_REFERENCE:
				return createMethodReferenceProposal(proposal);
			case CompletionProposal.METHOD_DECLARATION:
				return createMethodDeclarationProposal(proposal);
			case CompletionProposal.ANONYMOUS_CLASS_DECLARATION:
				return createAnonymousTypeProposal(proposal);
			case CompletionProposal.LABEL_REF:
				return createLabelProposal(proposal);
			case CompletionProposal.LOCAL_VARIABLE_REF:
			case CompletionProposal.VARIABLE_DECLARATION:
				return createLocalVariableProposal(proposal);
			case CompletionProposal.ANNOTATION_ATTRIBUTE_REF:
				return createAnnotationAttributeReferenceProposal(proposal);
			case CompletionProposal.POTENTIAL_METHOD_DECLARATION:
			default:
				return null;
		}
	}
	
	/**
	 * Creates the context information for a given method reference proposal.
	 * The passed proposal must be of kind {@link CompletionProposal#METHOD_REF}.
	 * 
	 * @param methodProposal the method proposal for which to create context information
	 * @return the context information for <code>methodProposal</code>
	 */
	protected final IContextInformation createMethodContextInformation(CompletionProposal methodProposal) {
		Assert.isTrue(methodProposal.getKind() == CompletionProposal.METHOD_REF);
		return new ProposalContextInformation(methodProposal);
	}

	/**
	 * Returns the compilation unit that the receiver operates on, or
	 * <code>null</code> if the <code>IJavaProject</code> constructor was
	 * used to create the receiver.
	 * 
	 * @return the compilation unit that the receiver operates on, or
	 *         <code>null</code>
	 */
	protected final ICompilationUnit getCompilationUnit() {
		return fCompilationUnit;
	}

	/**
	 * Returns the <code>CompletionContext</code> for this completion operation.
	 * 
	 * @return the <code>CompletionContext</code> for this completion operation
	 * @see CompletionRequestor#acceptContext(CompletionContext)
	 */
	protected final CompletionContext getContext() {
		return fContext;
	}
	
	/**
	 * Returns a cached image for the given descriptor.
	 * 
	 * @param descriptor the image descriptor to get an image for, may be
	 *        <code>null</code>
	 * @return the image corresponding to <code>descriptor</code>
	 */
	protected final Image getImage(ImageDescriptor descriptor) {
		return (descriptor == null) ? null : fRegistry.get(descriptor);
	}
	
	/**
	 * Returns the proposal label provider used by the receiver.
	 * 
	 * @return the proposal label provider used by the receiver
	 */
	protected final CompletionProposalLabelProvider getLabelProvider() {
		return fLabelProvider;
	}

	/**
	 * Returns the replacement length of a given completion proposal. The
	 * replacement length is usually the difference between the return values of
	 * <code>proposal.getReplaceEnd</code> and
	 * <code>proposal.getReplaceStart</code>, but this behavior may be
	 * overridden by calling {@link #setReplacementLength(int)}.
	 * 
	 * @param proposal the completion proposal to get the replacement length for
	 * @return the replacement length for <code>proposal</code>
	 */
	protected final int getLength(CompletionProposal proposal) {
		int start= proposal.getReplaceStart();
		int end= proposal.getReplaceEnd();
		int length;
		if (fUserReplacementLength == -1) {
			length= end - start;
		} else {
			length= fUserReplacementLength;
			// extend length to begin at start
			int behindCompletion= proposal.getCompletionLocation() + 1;
			if (start < behindCompletion) {
				length+= behindCompletion - start;
			}
		}
		return length;
	}
	
	/**
	 * Returns <code>true</code> if <code>proposal</code> is filtered, e.g.
	 * should not be proposed to the user, <code>false</code> if it is valid.
	 * <p>
	 * Subclasses may extends this method. The default implementation filters
	 * proposals set to be ignored via
	 * {@linkplain CompletionRequestor#setIgnored(int, boolean) setIgnored} and
	 * types set to be ignored in the preferences.
	 * </p>
	 * 
	 * @param proposal the proposal to filter
	 * @return <code>true</code> to filter <code>proposal</code>,
	 *         <code>false</code> to let it pass
	 */
	protected boolean isFiltered(CompletionProposal proposal) {
		if (isIgnored(proposal.getKind()))
			return true;
		char[] declaringType= getDeclaringType(proposal);
		return declaringType!= null && TypeFilter.isFiltered(declaringType);
	}
	
	/**
	 * Returns the type signature of the declaring type of a
	 * <code>CompletionProposal</code>, or <code>null</code> for proposals
	 * that do not have a declaring type. The return value is <em>not</em>
	 * <code>null</code> for proposals of the following kinds:
	 * <ul>
	 * <li>METHOD_DECLARATION</li>
	 * <li>METHOD_NAME_REFERENCE</li>
	 * <li>METHOD_REF</li>
	 * <li>ANNOTATION_ATTRIBUTE_REF</li>
	 * <li>POTENTIAL_METHOD_DECLARATION</li>
	 * <li>ANONYMOUS_CLASS_DECLARATION</li>
	 * <li>FIELD_REF</li>
	 * <li>PACKAGE_REF (returns the package, but no type)</li>
	 * <li>TYPE_REF</li>
	 * </ul>
	 * 
	 * @param proposal the completion proposal to get the declaring type for
	 * @return the type signature of the declaring type, or <code>null</code> if there is none
	 * @see Signature#toCharArray(char[])
	 */
	protected final char[] getDeclaringType(CompletionProposal proposal) {
		switch (proposal.getKind()) {
			case CompletionProposal.METHOD_DECLARATION:
			case CompletionProposal.METHOD_NAME_REFERENCE:
			case CompletionProposal.METHOD_REF:
			case CompletionProposal.ANNOTATION_ATTRIBUTE_REF:
			case CompletionProposal.POTENTIAL_METHOD_DECLARATION:
			case CompletionProposal.ANONYMOUS_CLASS_DECLARATION:
			case CompletionProposal.FIELD_REF:
				char[] declaration= proposal.getDeclarationSignature();
				// special methods may not have a declaring type: methods defined on arrays etc.
				// TODO remove when bug https://bugs.eclipse.org/bugs/show_bug.cgi?id=84690 gets fixed
				if (declaration == null)
					return "java.lang.Object".toCharArray(); //$NON-NLS-1$
				return Signature.toCharArray(declaration);
			case CompletionProposal.PACKAGE_REF:
				return proposal.getDeclarationSignature();
			case CompletionProposal.TYPE_REF:
				return Signature.toCharArray(proposal.getSignature());
			case CompletionProposal.LOCAL_VARIABLE_REF:
			case CompletionProposal.VARIABLE_DECLARATION:
			case CompletionProposal.KEYWORD:
			case CompletionProposal.LABEL_REF:
				return null;
			default:
				Assert.isTrue(false);
				return null;
		}
	}
	
	private void acceptPotentialMethodDeclaration(CompletionProposal proposal) {
		if (fCompilationUnit == null)
			return;
		
		String prefix= String.valueOf(proposal.getName());
		int completionStart= proposal.getReplaceStart();
		int completionEnd= proposal.getReplaceEnd();
		int relevance= computeRelevance(proposal);
	
		try {
			IJavaElement element= fCompilationUnit.getElementAt(proposal.getCompletionLocation() + 1);
			if (element != null) {
				IType type= (IType) element.getAncestor(IJavaElement.TYPE);
				if (type != null) {
					GetterSetterCompletionProposal.evaluateProposals(type, prefix, completionStart, completionEnd - completionStart, relevance + 1, fSuggestedMethodNames, fJavaProposals);
					MethodCompletionProposal.evaluateProposals(type, prefix, completionStart, completionEnd - completionStart, relevance, fSuggestedMethodNames, fJavaProposals);
				}
			}
		} catch (CoreException e) {
			JavaPlugin.log(e);
		}
	}
	
	private IJavaCompletionProposal createAnnotationAttributeReferenceProposal(CompletionProposal proposal) {
		String displayString= fLabelProvider.createLabelWithTypeAndDeclaration(proposal);
		ImageDescriptor descriptor= fLabelProvider.createMethodImageDescriptor(proposal);
		String completion= String.valueOf(proposal.getCompletion());
		return new JavaCompletionProposal(completion, proposal.getReplaceStart(), getLength(proposal), getImage(descriptor), displayString, computeRelevance(proposal));
	}
	
	private IJavaCompletionProposal createAnonymousTypeProposal(CompletionProposal proposal) {
		if (fCompilationUnit == null || fJavaProject == null)
			return null;
		
		String declaringType= SignatureUtil.stripSignatureToFQN(String.valueOf(proposal.getDeclarationSignature()));
		String completion= String.valueOf(proposal.getCompletion());
		int start= proposal.getReplaceStart();
		int length= getLength(proposal);
		int relevance= computeRelevance(proposal);
		
		String label= fLabelProvider.createAnonymousTypeLabel(proposal);
		
		JavaCompletionProposal javaProposal= new AnonymousTypeCompletionProposal(fJavaProject, fCompilationUnit, start, length, completion, label, declaringType, relevance);
		javaProposal.setProposalInfo(new AnonymousTypeProposalInfo(fJavaProject, proposal));
		return javaProposal;
	}	
	
	private IJavaCompletionProposal createFieldProposal(CompletionProposal proposal) {
		String completion= String.valueOf(proposal.getCompletion());
		int start= proposal.getReplaceStart();
		int length= getLength(proposal);
		String label= fLabelProvider.createLabelWithTypeAndDeclaration(proposal);
		Image image= getImage(fLabelProvider.createFieldImageDescriptor(proposal));
		int relevance= computeRelevance(proposal);

		JavaCompletionProposal javaProposal= new JavaCompletionProposal(completion, start, length, image, label, relevance);
		if (fJavaProject != null)
			javaProposal.setProposalInfo(new FieldProposalInfo(fJavaProject, proposal));
		
		javaProposal.setTriggerCharacters(VAR_TRIGGER);
		
		return javaProposal;
	}
	
	private IJavaCompletionProposal createKeywordProposal(CompletionProposal proposal) {
		String completion= String.valueOf(proposal.getCompletion());
		int start= proposal.getReplaceStart();
		int length= getLength(proposal);
		String label= fLabelProvider.createSimpleLabel(proposal);
		int relevance= computeRelevance(proposal);
		return new JavaCompletionProposal(completion, start, length, null, label, relevance);
	}
	
	private IJavaCompletionProposal createLabelProposal(CompletionProposal proposal) {
		String completion= String.valueOf(proposal.getCompletion());
		int start= proposal.getReplaceStart();
		int length= getLength(proposal);
		String label= fLabelProvider.createSimpleLabel(proposal);
		int relevance= computeRelevance(proposal);
		
		return new JavaCompletionProposal(completion, start, length, null, label, relevance);
	}
	
	private IJavaCompletionProposal createLocalVariableProposal(CompletionProposal proposal) {
		String completion= String.valueOf(proposal.getCompletion());
		int start= proposal.getReplaceStart();
		int length= getLength(proposal);
		Image image= getImage(fLabelProvider.createLocalImageDescriptor(proposal));
		String label= fLabelProvider.createSimpleLabelWithType(proposal);
		int relevance= computeRelevance(proposal);
		
		final JavaCompletionProposal javaProposal= new JavaCompletionProposal(completion, start, length, image, label, relevance);
		javaProposal.setTriggerCharacters(VAR_TRIGGER);
		return javaProposal;
	}
	
	private IJavaCompletionProposal createMethodDeclarationProposal(CompletionProposal proposal) {
		if (fCompilationUnit == null || fJavaProject == null)
			return null;
		
		String name= String.valueOf(proposal.getName());
		String[] paramTypes= Signature.getParameterTypes(String.valueOf(proposal.getSignature()));
		for (int index= 0; index < paramTypes.length; index++)
			paramTypes[index]= Signature.toString(paramTypes[index]);
		String completion= String.valueOf(proposal.getCompletion());
		int start= proposal.getReplaceStart();
		int length= getLength(proposal);
		
		String label= fLabelProvider.createOverrideMethodProposalLabel(proposal);

		JavaCompletionProposal javaProposal= new OverrideCompletionProposal(fJavaProject, fCompilationUnit, name, paramTypes, start, length, label, completion);
		javaProposal.setImage(getImage(fLabelProvider.createMethodImageDescriptor(proposal)));
		javaProposal.setProposalInfo(new MethodProposalInfo(fJavaProject, proposal));
		javaProposal.setRelevance(computeRelevance(proposal));

		fSuggestedMethodNames.add(new String(name));
		return javaProposal;
	}
	
	private IJavaCompletionProposal createMethodReferenceProposal(CompletionProposal methodProposal) {
		Image image= getImage(fLabelProvider.createMethodImageDescriptor(methodProposal));
		String displayName= fLabelProvider.createMethodProposalLabel(methodProposal);
		String completion= String.valueOf(methodProposal.getCompletion());
		int start= methodProposal.getReplaceStart();
		int relevance= computeRelevance(methodProposal);
		
		JavaCompletionProposal proposal= new JavaCompletionProposal(completion, start, getLength(methodProposal), image, displayName, relevance);
		
		if (fJavaProject != null)
			proposal.setProposalInfo(new MethodProposalInfo(fJavaProject, methodProposal));

		boolean hasParameters= Signature.getParameterCount(methodProposal.getSignature()) > 0;
		if (hasParameters) {
			IContextInformation contextInformation= createMethodContextInformation(methodProposal);
			proposal.setContextInformation(contextInformation);
		
			proposal.setTriggerCharacters(METHOD_WITH_ARGUMENTS_TRIGGERS);
			
			if (completion.endsWith(")")) { //$NON-NLS-1$
				// set the cursor before the closing bracket
				proposal.setCursorPosition(completion.length() - 1);
			}
		} else {
			proposal.setTriggerCharacters(METHOD_TRIGGERS);
		}
		
		return proposal;
	}
	
	private IJavaCompletionProposal createPackageProposal(CompletionProposal proposal) {
		String completion= String.valueOf(proposal.getCompletion());
		int start= proposal.getReplaceStart();
		int length= getLength(proposal);
		String label= fLabelProvider.createSimpleLabel(proposal);
		Image image= getImage(fLabelProvider.createPackageImageDescriptor(proposal));
		int relevance= computeRelevance(proposal);

		return new JavaCompletionProposal(completion, start, length, image, label, relevance);
	}
	
	private IJavaCompletionProposal createTypeProposal(CompletionProposal typeProposal) {
		char[] signature= typeProposal.getSignature();
		String packageName= String.valueOf(Signature.getSignatureQualifier(signature)); 
		String typeName= String.valueOf(Signature.getSignatureSimpleName(signature));
		
		String completion= String.valueOf(typeProposal.getCompletion());
		int start= typeProposal.getReplaceStart();
		ImageDescriptor descriptor= fLabelProvider.createTypeImageDescriptor(typeProposal);
		String label= fLabelProvider.createTypeProposalLabel(typeProposal);
		int relevance= computeRelevance(typeProposal);

		JavaCompletionProposal proposal= new JavaTypeCompletionProposal(completion, fCompilationUnit, start, getLength(typeProposal), getImage(descriptor), label, relevance, typeName, packageName);

		if (fJavaProject != null)
			proposal.setProposalInfo(new TypeProposalInfo(fJavaProject, typeProposal));
		
		proposal.setTriggerCharacters(TYPE_TRIGGERS);
		
		return proposal;
	}
}
