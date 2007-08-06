/*******************************************************************************
 * Copyright (c) 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.corext.refactoring.structure;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

import org.eclipse.text.edits.TextEdit;

import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;

import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.NamingConventions;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.compiler.IScanner;
import org.eclipse.jdt.core.compiler.ITerminalSymbols;
import org.eclipse.jdt.core.compiler.InvalidInputException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.Modifier.ModifierKeyword;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import org.eclipse.jdt.internal.corext.codemanipulation.ContextSensitiveImportRewriteContext;
import org.eclipse.jdt.internal.corext.codemanipulation.StubUtility;
import org.eclipse.jdt.internal.corext.dom.TokenScanner;
import org.eclipse.jdt.internal.corext.refactoring.ParameterInfo;
import org.eclipse.jdt.internal.corext.refactoring.RefactoringCoreMessages;
import org.eclipse.jdt.internal.corext.refactoring.changes.CreateCompilationUnitChange;
import org.eclipse.jdt.internal.corext.refactoring.changes.CreatePackageChange;
import org.eclipse.jdt.internal.corext.refactoring.util.ResourceUtil;
import org.eclipse.jdt.internal.corext.util.JavaModelUtil;

import org.eclipse.jdt.ui.CodeGeneration;

import org.eclipse.jdt.internal.ui.JavaPlugin;

public class ParameterObjectFactory {

	private String fClassName;
	private boolean fCreateGetter;
	private boolean fCreateSetter;
	private String fEnclosingType;
	private String fPackage;
	private List fVariables;

	public ParameterObjectFactory() {
		super();
	}

	public static class CreationListener {
		/**
		 * Notifies that a getter has been created
		 * @param cuRewrite the rewriter
		 * @param getter the new getter
		 * @param pi the parameter info
		 */
		public void getterCreated(CompilationUnitRewrite cuRewrite, MethodDeclaration getter, ParameterInfo pi){}
		/**
		 * Notifies that a setter has been created
		 * @param cuRewrite the rewriter
		 * @param setter the new setter
		 * @param pi the parameter info
		 */
		public void setterCreated(CompilationUnitRewrite cuRewrite, MethodDeclaration setter, ParameterInfo pi){}
		/**
		 * Notifies that a field has been created
		 * @param cuRewrite the rewriter
		 * @param field the new field
		 * @param pi the parameter info
		 */
		public void fieldCreated(CompilationUnitRewrite cuRewrite, FieldDeclaration field, ParameterInfo pi){}
		/**
		 * Notifies that a constructor has been created
		 * @param cuRewrite the rewriter
		 * @param constructor the new constructor
		 */
		public void constructorCreated(CompilationUnitRewrite cuRewrite, MethodDeclaration constructor){}
		/**
		 * Notifies that a type declaration has been created
		 * @param cuRewrite the rewriter
		 * @param declaration the new declaration
		 */
		public void typeCreated(CompilationUnitRewrite cuRewrite, TypeDeclaration declaration) {}
		
		protected static ASTNode moveNode(CompilationUnitRewrite cuRewrite, ASTNode node) {
			ASTRewrite rewrite= cuRewrite.getASTRewrite();
			if (rewrite.getAST() != node.getAST())
				return ASTNode.copySubtree(rewrite.getAST(), node);
			return rewrite.createMoveTarget(node);
		}
		/**
		 * Return whether the setter should be created for this field. This method is only called when 
		 * the global createSetters is set and the parameterInfo is marked for field creation.
		 * @param pi the parameter info
		 * @return <code>true</code> if a setter should be created
		 */
		public boolean isCreateSetter(ParameterInfo pi) {
			return !Modifier.isFinal(pi.getOldBinding().getModifiers());
		}
		/**
		 * Return whether the getter should be created for this field. This method is only called when 
		 * the global createGetters is set and the parameterInfo is marked for field creation.
		 * @param pi the parameter info
		 * @return <code>true</code> if a getter should be created
		 */
		public boolean isCreateGetter(ParameterInfo pi) {
			return true;
		}
		/**
		 * Return whether the field should appear in the constructor
		 * @param pi the parameter info
		 * @return <code>true</code> if the field should appear
		 */
		public boolean isUseInConstructor(ParameterInfo pi) {
			return true;
		}
	}

	/**
	 * Creates a new TypeDeclaration for the parameterInfo objects.
	 * 
	 * @param declaringType the fully qualified name of the type
	 * @param cuRewrite the {@link CompilationUnitRewrite} that will be used for creation
	 * @param listener the creation listener or null
	 * @return the new declaration
	 * @throws CoreException
	 */
	public TypeDeclaration createClassDeclaration(String declaringType, CompilationUnitRewrite cuRewrite, CreationListener listener) throws CoreException {
		AST ast= cuRewrite.getAST();
		if (listener == null)
			listener= new CreationListener();
		TypeDeclaration typeDeclaration= ast.newTypeDeclaration();
		typeDeclaration.setName(ast.newSimpleName(fClassName));
		List body= typeDeclaration.bodyDeclarations();
		for (Iterator iter= fVariables.iterator(); iter.hasNext();) {
			ParameterInfo pi= (ParameterInfo) iter.next();
			if (isValidField(pi)) {
				FieldDeclaration declaration= createField(pi, cuRewrite);
				listener.fieldCreated(cuRewrite, declaration, pi);
				body.add(declaration);
			}
		}
		MethodDeclaration constructor= createConstructor(declaringType, cuRewrite, listener);
		listener.constructorCreated(cuRewrite, constructor);
		body.add(constructor);
		for (Iterator iter= fVariables.iterator(); iter.hasNext();) {
			ParameterInfo pi= (ParameterInfo) iter.next();
			if (fCreateGetter && isValidField(pi) && listener.isCreateGetter(pi)) {
				MethodDeclaration getter= createGetter(pi, declaringType, cuRewrite);
				listener.getterCreated(cuRewrite, getter, pi);
				body.add(getter);
			}
			if (fCreateSetter && isValidField(pi) && listener.isCreateSetter(pi)) {
				MethodDeclaration setter= createSetter(pi, declaringType, cuRewrite);
				listener.setterCreated(cuRewrite, setter, pi);
				body.add(setter);
			}
		}
		listener.typeCreated(cuRewrite, typeDeclaration);
		return typeDeclaration;
	}

	private MethodDeclaration createConstructor(String declaringTypeName, CompilationUnitRewrite cuRewrite, CreationListener listener) throws CoreException {
		AST ast= cuRewrite.getAST();
		ICompilationUnit unit= cuRewrite.getCu();
		IJavaProject project= unit.getJavaProject();

		MethodDeclaration methodDeclaration= ast.newMethodDeclaration();
		methodDeclaration.setName(ast.newSimpleName(fClassName));
		methodDeclaration.setConstructor(true);
		methodDeclaration.modifiers().add(ast.newModifier(ModifierKeyword.PUBLIC_KEYWORD));
		String lineDelimiter= StubUtility.getLineDelimiterUsed(unit);
		if (createComments(project)) {
			String comment= CodeGeneration.getMethodComment(unit, declaringTypeName, methodDeclaration, null, lineDelimiter);
			if (comment != null) {
				Javadoc doc= (Javadoc) cuRewrite.getASTRewrite().createStringPlaceholder(comment, ASTNode.JAVADOC);
				methodDeclaration.setJavadoc(doc);
			}
		}
		List parameters= methodDeclaration.parameters();
		Block block= ast.newBlock();
		methodDeclaration.setBody(block);
		List statements= block.statements();
		List validParameter= new ArrayList();
		for (Iterator iter= fVariables.iterator(); iter.hasNext();) {
			ParameterInfo pi= (ParameterInfo) iter.next();
			if (isValidField(pi) && listener.isUseInConstructor(pi)) {
					validParameter.add(pi);
			}
		}

		ArrayList usedParameter= new ArrayList();
		for (Iterator iter= validParameter.iterator(); iter.hasNext();) {
			ParameterInfo pi= (ParameterInfo) iter.next();
			SingleVariableDeclaration svd= ast.newSingleVariableDeclaration();
			ITypeBinding typeBinding= pi.getNewTypeBinding();
			if (!iter.hasNext() && typeBinding.isArray() && pi.isOldVarargs()) {
				int dimensions= typeBinding.getDimensions();
				if (dimensions == 1) {
					typeBinding= typeBinding.getComponentType();
				} else {
					typeBinding= typeBinding.createArrayType(dimensions - 1);
				}
				svd.setVarargs(true);
			}

			String paramName= getParameterName(pi, project, usedParameter);
			usedParameter.add(paramName);
			
			Type fieldType= importBinding(typeBinding, cuRewrite);
			svd.setType(fieldType);
			svd.setName(ast.newSimpleName(paramName));
			parameters.add(svd);
			Expression leftHandSide;
			if (paramName.equals(pi.getNewName()) || StubUtility.useThisForFieldAccess(project)) {
				FieldAccess fieldAccess= ast.newFieldAccess();
				fieldAccess.setName(ast.newSimpleName(pi.getNewName()));
				fieldAccess.setExpression(ast.newThisExpression());
				leftHandSide= fieldAccess;
			} else {
				leftHandSide= ast.newSimpleName(pi.getNewName());
			}
			Assignment assignment= ast.newAssignment();
			assignment.setLeftHandSide(leftHandSide);
			assignment.setRightHandSide(ast.newSimpleName(paramName));
			statements.add(ast.newExpressionStatement(assignment));
		}
		return methodDeclaration;
	}

	private String getParameterName(ParameterInfo pi, IJavaProject project, ArrayList usedParameter) {
		String fieldName= pi.getNewName();
		String strippedName= NamingConventions.removePrefixAndSuffixForFieldName(project, fieldName, 0);
		String[] suggestions= StubUtility.getVariableNameSuggestions(StubUtility.PARAMETER, project, strippedName, 0, usedParameter, true);
		return suggestions[0];
	}


	public static Type importBinding(ITypeBinding typeBinding, CompilationUnitRewrite cuRewrite) {
		int declaredModifiers= typeBinding.getDeclaredModifiers();
		AST ast= cuRewrite.getAST();
		if (Modifier.isPrivate(declaredModifiers) || Modifier.isProtected(declaredModifiers)) {
			return ast.newSimpleType(ast.newSimpleName(typeBinding.getName()));
		}
		Type type= cuRewrite.getImportRewrite().addImport(typeBinding, cuRewrite.getAST());
		cuRewrite.getImportRemover().registerAddedImports(type);
		return type;
	}

	private FieldDeclaration createField(ParameterInfo pi, CompilationUnitRewrite cuRewrite) throws CoreException {
		AST ast= cuRewrite.getAST();
		ICompilationUnit unit= cuRewrite.getCu();

		VariableDeclarationFragment fragment= ast.newVariableDeclarationFragment();
		String lineDelim= StubUtility.getLineDelimiterUsed(unit);
		SimpleName fieldName= ast.newSimpleName(pi.getNewName());
		fragment.setName(fieldName);
		FieldDeclaration declaration= ast.newFieldDeclaration(fragment);
		if (createComments(unit.getJavaProject())) {
			String comment= StubUtility.getFieldComment(unit, pi.getNewTypeName(), pi.getNewName(), lineDelim);
			if (comment != null) {
				Javadoc doc= (Javadoc) cuRewrite.getASTRewrite().createStringPlaceholder(comment, ASTNode.JAVADOC);
				declaration.setJavadoc(doc);
			}
		}
		List modifiers= new ArrayList();
		if (fCreateGetter) {
			modifiers.add(ast.newModifier(ModifierKeyword.PRIVATE_KEYWORD));
		} else {
			modifiers.add(ast.newModifier(ModifierKeyword.PUBLIC_KEYWORD));
		}
		declaration.modifiers().addAll(modifiers);
		declaration.setType(importBinding(pi.getNewTypeBinding(), cuRewrite));
		return declaration;
	}

	public Expression createFieldReadAccess(ParameterInfo pi, String paramName, AST ast, IJavaProject project, boolean useSuper) {
		SuperFieldAccess sf= null;
		if (useSuper) {
			sf= ast.newSuperFieldAccess();
			sf.setName(ast.newSimpleName(paramName));
		}
		if (!fCreateGetter) {
			return createFieldAccess(pi, paramName, ast, useSuper, sf);
		} else {
			MethodInvocation method= ast.newMethodInvocation();
			method.setName(ast.newSimpleName(getGetterName(pi, ast, project)));
			if (useSuper)
				method.setExpression(sf);
			else
				method.setExpression(ast.newSimpleName(paramName));
			return method;
		}
	}

	private Expression createFieldAccess(ParameterInfo pi, String paramName, AST ast, boolean useSuper, SuperFieldAccess sf) {
		if (useSuper) {
			FieldAccess fa;
			fa= ast.newFieldAccess();
			fa.setExpression(sf);
			fa.setName(ast.newSimpleName(pi.getNewName()));
			return fa;
		}
		return ast.newName(new String[] { paramName, pi.getNewName() });
	}

	public Expression createFieldWriteAccess(ParameterInfo pi, String paramName, AST ast, IJavaProject project, Expression assignedValue, Expression qualifier, boolean useSuper) {
		SuperFieldAccess sf= null;
		if (useSuper) {
			sf= ast.newSuperFieldAccess();
			sf.setName(ast.newSimpleName(paramName));
		}
		if (!fCreateSetter) {
			if (qualifier != null) {
				FieldAccess fa2= ast.newFieldAccess();
				if (useSuper) {
					fa2.setExpression(sf);
				} else {
					fa2.setExpression(createParamAccess(paramName, ast, qualifier));
				}
				fa2.setName(ast.newSimpleName(pi.getNewName()));
				Assignment assignment= ast.newAssignment();
				assignment.setLeftHandSide(fa2);
				assignment.setRightHandSide(assignedValue);
				return assignment;
			} else {
				return createFieldAccess(pi, paramName, ast, useSuper, sf);
			}
		} else {
			SimpleName setterName= ast.newSimpleName(getSetterName(pi, ast, project));
			MethodInvocation method= ast.newMethodInvocation();
			method.setName(setterName);
			Expression qualifierExpression;
			if (useSuper) {
				qualifierExpression= sf;
			} else {
				if (qualifier != null) {
					qualifierExpression= createParamAccess(paramName, ast, qualifier);
				} else {
					qualifierExpression= ast.newSimpleName(paramName);
				}
			}
			method.setExpression(qualifierExpression);
			method.arguments().add(assignedValue);
			return method;
		}
	}

	private FieldAccess createParamAccess(String paramName, AST ast, Expression qualifier) {
		FieldAccess fa= ast.newFieldAccess();
		fa.setExpression(qualifier);
		fa.setName(ast.newSimpleName(paramName));
		return fa;
	}

	private MethodDeclaration createGetter(ParameterInfo pi, String declaringType, CompilationUnitRewrite cuRewrite) throws CoreException {
		AST ast= cuRewrite.getAST();
		ICompilationUnit cu= cuRewrite.getCu();
		IJavaProject project= cu.getJavaProject();

		MethodDeclaration methodDeclaration= ast.newMethodDeclaration();
		String fieldName= pi.getNewName();
		String getterName= getGetterName(pi, ast, project);
		String lineDelim= StubUtility.getLineDelimiterUsed(cu);
		String bareFieldname= NamingConventions.removePrefixAndSuffixForFieldName(project, fieldName, Flags.AccPrivate);
		if (createComments(project)) {
			String comment= CodeGeneration.getGetterComment(cu, declaringType, getterName, fieldName, pi.getNewTypeName(), bareFieldname, lineDelim);
			if (comment != null)
				methodDeclaration.setJavadoc((Javadoc) cuRewrite.getASTRewrite().createStringPlaceholder(comment, ASTNode.JAVADOC));
		}
		methodDeclaration.setName(ast.newSimpleName(getterName));
		methodDeclaration.setReturnType2(importBinding(pi.getNewTypeBinding(), cuRewrite));
		methodDeclaration.modifiers().add(ast.newModifier(ModifierKeyword.PUBLIC_KEYWORD));
		Block block= ast.newBlock();
		methodDeclaration.setBody(block);
		boolean useThis= StubUtility.useThisForFieldAccess(project);
		if (useThis) {
			fieldName= "this." + fieldName; //$NON-NLS-1$
		}
		String bodyContent= CodeGeneration.getGetterMethodBodyContent(cu, declaringType, getterName, fieldName, lineDelim);
		ASTNode getterBody= cuRewrite.getASTRewrite().createStringPlaceholder(bodyContent, ASTNode.EXPRESSION_STATEMENT);
		block.statements().add(getterBody);
		return methodDeclaration;
	}

	public ExpressionStatement createInitializer(ParameterInfo pi, String paramName, CompilationUnitRewrite cuRewrite) {
		AST ast= cuRewrite.getAST();

		VariableDeclarationFragment fragment= ast.newVariableDeclarationFragment();
		fragment.setName(ast.newSimpleName(pi.getOldName()));
		fragment.setInitializer(createFieldReadAccess(pi, paramName, ast, cuRewrite.getCu().getJavaProject(), false));
		VariableDeclarationExpression declaration= ast.newVariableDeclarationExpression(fragment);
		IVariableBinding variable= pi.getOldBinding();
		declaration.setType(importBinding(pi.getNewTypeBinding(), cuRewrite));
		int modifiers= variable.getModifiers();
		List newModifiers= ast.newModifiers(modifiers);
		declaration.modifiers().addAll(newModifiers);
		return ast.newExpressionStatement(declaration);
	}

	private MethodDeclaration createSetter(ParameterInfo pi, String declaringType, CompilationUnitRewrite cuRewrite) throws CoreException {
		AST ast= cuRewrite.getAST();
		ICompilationUnit cu= cuRewrite.getCu();
		IJavaProject project= cu.getJavaProject();

		MethodDeclaration methodDeclaration= ast.newMethodDeclaration();
		String fieldName= pi.getNewName();
		String setterName= getSetterName(pi, ast, project);
		String lineDelim= StubUtility.getLineDelimiterUsed(cu);
		String bareFieldname= NamingConventions.removePrefixAndSuffixForFieldName(project, fieldName, Flags.AccPrivate);
		String paramName= StubUtility.suggestArgumentName(project, bareFieldname, null);
		if (createComments(project)) {
			String comment= CodeGeneration.getSetterComment(cu, declaringType, setterName, fieldName, pi.getNewTypeName(), paramName, bareFieldname, lineDelim);
			if (comment != null)
				methodDeclaration.setJavadoc((Javadoc) cuRewrite.getASTRewrite().createStringPlaceholder(comment, ASTNode.JAVADOC));
		}
		methodDeclaration.setName(ast.newSimpleName(setterName));
		methodDeclaration.modifiers().add(ast.newModifier(ModifierKeyword.PUBLIC_KEYWORD));
		SingleVariableDeclaration variable= ast.newSingleVariableDeclaration();
		variable.setType(importBinding(pi.getNewTypeBinding(), cuRewrite));
		variable.setName(ast.newSimpleName(paramName));
		methodDeclaration.parameters().add(variable);
		Block block= ast.newBlock();
		methodDeclaration.setBody(block);
		boolean useThis= StubUtility.useThisForFieldAccess(project);
		if (useThis || fieldName.equals(paramName)) {
			fieldName= "this." + fieldName; //$NON-NLS-1$
		}
		String bodyContent= CodeGeneration.getSetterMethodBodyContent(cu, declaringType, setterName, fieldName, paramName, lineDelim);
		ASTNode setterBody= cuRewrite.getASTRewrite().createStringPlaceholder(bodyContent, ASTNode.EXPRESSION_STATEMENT);
		block.statements().add(setterBody);
		return methodDeclaration;
	}

	public Type createType(boolean asTopLevelClass, CompilationUnitRewrite cuRewrite, int position) {
		String concatenateName= null;
		if (asTopLevelClass) {
			concatenateName= JavaModelUtil.concatenateName(fPackage, fClassName);
		} else {
			concatenateName= JavaModelUtil.concatenateName(fEnclosingType, fClassName);
		}
		ImportRewrite importRewrite= cuRewrite.getImportRewrite();
		ContextSensitiveImportRewriteContext context= new ContextSensitiveImportRewriteContext(cuRewrite.getRoot(), position, importRewrite);
		String addedImport= importRewrite.addImport(concatenateName, context);
		cuRewrite.getImportRemover().registerAddedImport(addedImport);
		AST ast= cuRewrite.getAST();
		return ast.newSimpleType(ast.newName(addedImport));
	}

	public String getClassName() {
		return fClassName;
	}

	public String getEnclosingType() {
		return fEnclosingType;
	}

	private String getGetterName(ParameterInfo pi, AST ast, IJavaProject project) {
		ITypeBinding type= pi.getNewTypeBinding();
		boolean isBoolean= ast.resolveWellKnownType("boolean").isEqualTo(type) || ast.resolveWellKnownType("java.lang.Boolean").isEqualTo(type); //$NON-NLS-1$//$NON-NLS-2$
		return NamingConventions.suggestGetterName(project, pi.getNewName(), Flags.AccPublic, isBoolean, null);
	}

	public String getPackage() {
		return fPackage;
	}

	public ParameterInfo getParameterInfo(String identifier) {
		for (Iterator iter= fVariables.iterator(); iter.hasNext();) {
			ParameterInfo pi= (ParameterInfo) iter.next();
			if (pi.getOldName().equals(identifier))
				return pi;
		}
		return null;
	}

	private String getSetterName(ParameterInfo pi, AST ast, IJavaProject project) {
		ITypeBinding type= pi.getNewTypeBinding();
		boolean isBoolean= ast.resolveWellKnownType("boolean").isEqualTo(type) || ast.resolveWellKnownType("java.lang.Boolean").isEqualTo(type); //$NON-NLS-1$//$NON-NLS-2$
		return NamingConventions.suggestSetterName(project, pi.getNewName(), Flags.AccPublic, isBoolean, null);
	}

	public boolean isCreateGetter() {
		return fCreateGetter;
	}

	public boolean isCreateSetter() {
		return fCreateSetter;
	}

	private boolean isValidField(ParameterInfo pi) {
		return pi.isCreateField() && !pi.isAdded();
	}

	public void moveDown(ParameterInfo selected) {
		int idx= fVariables.indexOf(selected);
		Assert.isTrue(idx >= 0 && idx < fVariables.size() - 1);
		int nextIdx= idx + 1;
		ParameterInfo next= (ParameterInfo) fVariables.get(nextIdx);
		if (next.isAdded()) {
			nextIdx++;
			Assert.isTrue(nextIdx <= fVariables.size() - 1);
			next= (ParameterInfo) fVariables.get(nextIdx);
		}
		fVariables.set(idx, next);
		fVariables.set(nextIdx, selected);
	}

	public void moveUp(ParameterInfo selected) {
		int idx= fVariables.indexOf(selected);
		Assert.isTrue(idx > 0);
		int prevIdx= idx - 1;
		ParameterInfo prev= (ParameterInfo) fVariables.get(prevIdx);
		if (prev.isAdded()) {
			prevIdx--;
			Assert.isTrue(prevIdx >= 0);
			prev= (ParameterInfo) fVariables.get(prevIdx);
		}
		fVariables.set(idx, prev);
		fVariables.set(prevIdx, selected);
	}

	public void setClassName(String className) {
		fClassName= className;
	}

	public void setCreateGetter(boolean createGetter) {
		fCreateGetter= createGetter;
	}

	public void setCreateSetter(boolean createSetter) {
		fCreateSetter= createSetter;
	}

	public void setEnclosingType(String enclosingType) {
		fEnclosingType= enclosingType;
	}

	public void setPackage(String typeQualifier) {
		fPackage= typeQualifier;
	}

	public void setVariables(List parameters) {
		fVariables= parameters;
	}

	/**
	 * Updates the position of the newly inserted parameterObject so that it is
	 * directly after the first checked parameter
	 * 
	 * @param parameterObjectReference
	 */
	public void updateParameterPosition(ParameterInfo parameterObjectReference) {
		fVariables.remove(parameterObjectReference);
		for (ListIterator iterator= fVariables.listIterator(); iterator.hasNext();) {
			ParameterInfo pi= (ParameterInfo) iterator.next();
			if (isValidField(pi)) {
				iterator.add(parameterObjectReference);
				return;
			}
		}
	}

	private boolean createComments(IJavaProject project) {
		return StubUtility.doAddComments(project);
	}


	public List/*<Change>*/createTopLevelParameterObject(IPackageFragmentRoot packageFragmentRoot, CreationListener listener) throws CoreException {
		List changes= new ArrayList();
		IPackageFragment packageFragment= packageFragmentRoot.getPackageFragment(getPackage());
		if (!packageFragment.exists()) {
			changes.add(new CreatePackageChange(packageFragment));
		}
		ICompilationUnit unit= packageFragment.getCompilationUnit(getClassName() + JavaModelUtil.DEFAULT_CU_SUFFIX);
		Assert.isTrue(!unit.exists());
		IJavaProject javaProject= unit.getJavaProject();
		ICompilationUnit workingCopy= unit.getWorkingCopy(null);

		try {
			// create stub with comments and dummy type
			String lineDelimiter= StubUtility.getLineDelimiterUsed(javaProject);
			String fileComment= getFileComment(workingCopy, lineDelimiter);
			String typeComment= getTypeComment(workingCopy, lineDelimiter);
			String content= CodeGeneration.getCompilationUnitContent(workingCopy, fileComment, typeComment, "class " + getClassName() + "{}", lineDelimiter); //$NON-NLS-1$ //$NON-NLS-2$
			workingCopy.getBuffer().setContents(content);

			CompilationUnitRewrite cuRewrite= new CompilationUnitRewrite(workingCopy);
			ASTRewrite rewriter= cuRewrite.getASTRewrite();
			CompilationUnit root= cuRewrite.getRoot();
			AST ast= cuRewrite.getAST();
			ImportRewrite importRewrite= cuRewrite.getImportRewrite();

			// retrieve&replace dummy type with real class
			ListRewrite types= rewriter.getListRewrite(root, CompilationUnit.TYPES_PROPERTY);
			ASTNode dummyType= (ASTNode) types.getOriginalList().get(0);
			String newTypeName= JavaModelUtil.concatenateName(getPackage(), getClassName());
			TypeDeclaration classDeclaration= createClassDeclaration(newTypeName, cuRewrite, listener);
			classDeclaration.modifiers().add(ast.newModifier(ModifierKeyword.PUBLIC_KEYWORD));
			Javadoc javadoc= (Javadoc) dummyType.getStructuralProperty(TypeDeclaration.JAVADOC_PROPERTY);
			rewriter.set(classDeclaration, TypeDeclaration.JAVADOC_PROPERTY, javadoc, null);
			types.replace(dummyType, classDeclaration, null);

			// Apply rewrites and discard workingcopy
			// Using CompilationUnitRewrite.createChange() leads to strange
			// results
			String charset= ResourceUtil.getFile(unit).getCharset(false);
			Document document= new Document(content);
			try {
				rewriter.rewriteAST().apply(document);
				TextEdit rewriteImports= importRewrite.rewriteImports(null);
				rewriteImports.apply(document);
			} catch (BadLocationException e) {
				throw new CoreException(new Status(IStatus.ERROR, JavaPlugin.getPluginId(), RefactoringCoreMessages.IntroduceParameterObjectRefactoring_parameter_object_creation_error, e));
			}
			String docContent= document.get();
			CreateCompilationUnitChange compilationUnitChange= new CreateCompilationUnitChange(unit, docContent, charset);
			changes.add(compilationUnitChange);
		} finally {
			workingCopy.discardWorkingCopy();
		}
		return changes;
	}

	public List/*<Change>*/createTopLevelParameterObject(IPackageFragmentRoot packageFragmentRoot) throws CoreException {
		return createTopLevelParameterObject(packageFragmentRoot, null);
	}

	protected String getFileComment(ICompilationUnit parentCU, String lineDelimiter) throws CoreException {
		if (StubUtility.doAddComments(parentCU.getJavaProject())) {
			return CodeGeneration.getFileComment(parentCU, lineDelimiter);
		}
		return null;

	}

	protected String getTypeComment(ICompilationUnit parentCU, String lineDelimiter) throws CoreException {
		if (StubUtility.doAddComments(parentCU.getJavaProject())) {
			StringBuffer typeName= new StringBuffer();
			typeName.append(getClassName());
			String[] typeParamNames= new String[0];
			String comment= CodeGeneration.getTypeComment(parentCU, typeName.toString(), typeParamNames, lineDelimiter);
			if (comment != null && isValidComment(comment)) {
				return comment;
			}
		}
		return null;
	}

	private boolean isValidComment(String template) {
		IScanner scanner= ToolFactory.createScanner(true, false, false, false);
		scanner.setSource(template.toCharArray());
		try {
			int next= scanner.getNextToken();
			while (TokenScanner.isComment(next)) {
				next= scanner.getNextToken();
			}
			return next == ITerminalSymbols.TokenNameEOF;
		} catch (InvalidInputException e) {
		}
		return false;
	}

}
