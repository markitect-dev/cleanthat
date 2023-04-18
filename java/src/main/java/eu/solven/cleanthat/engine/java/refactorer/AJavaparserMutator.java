/*
 * Copyright 2023 Benoit Lacelle - SOLVEN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.solven.cleanthat.engine.java.refactorer;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.resolution.Resolvable;
import com.github.javaparser.resolution.SymbolResolver;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;

import eu.solven.cleanthat.SuppressCleanthat;
import eu.solven.cleanthat.engine.java.refactorer.function.OnMethodName;
import eu.solven.cleanthat.engine.java.refactorer.helpers.MethodCallExprHelpers;
import eu.solven.cleanthat.engine.java.refactorer.meta.ICountMutatorIssues;
import eu.solven.cleanthat.engine.java.refactorer.meta.IJavaparserMutator;
import eu.solven.cleanthat.engine.java.refactorer.meta.IMutator;
import eu.solven.pepper.logging.PepperLogHelper;

/**
 * Enables common behavior to JavaParser-based rules
 *
 * @author Benoit Lacelle
 */
@SuppressWarnings("PMD.GodClass")
public abstract class AJavaparserMutator implements IJavaparserMutator, ICountMutatorIssues {
	private static final Logger LOGGER = LoggerFactory.getLogger(AJavaparserMutator.class);

	private final AtomicInteger nbIdempotencyIssues = new AtomicInteger();
	private final AtomicInteger nbReplaceIssues = new AtomicInteger();
	private final AtomicInteger nbRemoveIssues = new AtomicInteger();

	@Override
	public int getNbIdempotencyIssues() {
		return nbIdempotencyIssues.get();
	}

	@Override
	public int getNbReplaceIssues() {
		return nbReplaceIssues.get();
	}

	@Override
	public int getNbRemoveIssues() {
		return nbRemoveIssues.get();
	}

	@Override
	public Optional<Node> walkAst(Node tree) {
		var transformed = new AtomicBoolean();
		tree.walk(node -> {
			Optional<NodeWithAnnotations> optSuppressedParent =
					node.findAncestor(n -> n.isAnnotationPresent(SuppressCleanthat.class), NodeWithAnnotations.class);
			Optional<Node> optSuppressedChildren = node.findFirst(Node.class,
					n -> n instanceof NodeWithAnnotations<?>
							&& ((NodeWithAnnotations<?>) n).isAnnotationPresent(SuppressCleanthat.class));
			if (node instanceof NodeWithAnnotations
					&& ((NodeWithAnnotations<?>) node).isAnnotationPresent(SuppressCleanthat.class)
					|| optSuppressedParent.isPresent()
					|| optSuppressedChildren.isPresent()) {
				LOGGER.debug("We skip {} due to {}", node, SuppressCleanthat.class.getName());
				return;
			} else if (node.findCompilationUnit().isEmpty()) {
				LOGGER.debug("We skip {} as it or one of tis ancestor has been dropped from the AST", node);
				return;
			}

			boolean hasTransformed;
			try {
				LOGGER.trace("{} is going over {}",
						this.getClass().getSimpleName(),
						PepperLogHelper.getObjectAndClass(node));
				hasTransformed = processNotRecursively(node);
			} catch (RuntimeException e) {
				String rangeInSourceCode = "Around lines: " + node.getTokenRange().map(Object::toString).orElse("-");
				var messageForIssueReporting = messageForIssueReporting(this, node);
				throw new IllegalArgumentException(
						"Issue with a cleanthat mutator. " + rangeInSourceCode + " " + messageForIssueReporting,
						e);
			}

			if (hasTransformed) {
				LOGGER.debug("{} transformed something into `{}`", this, node);
				idempotencySanityCheck(node);
				transformed.set(true);
			}
		});
		if (transformed.get()) {
			return Optional.of(tree);
		} else {
			return Optional.empty();
		}
	}

	private void idempotencySanityCheck(Node node) {
		if (node.getParentNode().isEmpty()) {
			// This node has seemingly been removed from its parent
			return;
		} else if (node.findCompilationUnit().isEmpty()) {
			// This node has no compilation unit: either it was not in a compilationUnit from the start (e.g. in a
			// unitTest)
			// or it is ancestor which has been replaced (e.g. we analyzed the ancestors to decide to remove an
			// ancestor: current node still has a parent, but no compilationUnit anymore)
			return;
		}

		var transformAgain = processNotRecursively(node);
		// 'NoOp' is a special parserRule which always returns true even while it did not transform the code
		if (!this.getIds().contains(IMutator.ID_NOOP) && transformAgain) {
			// This may restore the initial code (e.g. if the rule is switching 'a.equals(b)' to 'b.equals(a)'
			// to again 'a.equals(b)')
			nbIdempotencyIssues.incrementAndGet();
			var messageForIssueReporting = messageForIssueReporting(this, node);
			LOGGER.warn("A mutator is not idem-potent. {}", messageForIssueReporting);
		}
	}

	public static String messageForIssueReporting(IMutator mutator, Node node) {
		var faultyCode = node.toString();

		String messageForIssueReporting =
				"\r\n\r\nPlease report it to '" + "https://github.com/solven-eu/cleanthat/issues"
						+ "' referring the faulty mutator: '"
						+ mutator.getClass().getName()
						+ " with as testCase: \r\n\r\n"
						+ faultyCode;
		return messageForIssueReporting;
	}

	protected boolean processNotRecursively(Node node) {
		Optional<Node> optReplacement = replaceNode(node);

		if (optReplacement.isPresent()) {
			var replacement = optReplacement.get();
			return tryReplace(node, replacement);
		} else {
			return false;
		}
	}

	protected boolean tryReplace(Node node, Node replacement) {
		if (cancelDueToComment(node)) {
			LOGGER.info("We skip replacing {} due to the presence of a comment", node);
			return false;
		}

		LOGGER.info("Turning `{}` into `{}`", node, replacement);

		var result = node.replace(replacement);

		if (!result) {
			nbReplaceIssues.incrementAndGet();
			LOGGER.warn("We failed turning `{}` into `{}`", node, replacement);
		}

		return result;
	}

	protected boolean tryRemove(Node node) {
		if (cancelDueToComment(node)) {
			LOGGER.info("We skip removing {} due to the presence of a comment", node);
			return false;
		}

		var nodeParentAsString = node.getParentNode().map(n -> n.getClass().getSimpleName()).orElse("-");
		LOGGER.info("Removing `{}` from a {}", node, nodeParentAsString);

		var result = node.remove();

		if (!result) {
			nbRemoveIssues.incrementAndGet();
			LOGGER.warn("Failed removing `{}` from a {}", node, nodeParentAsString);
		}
		return result;
	}

	protected boolean cancelDueToComment(Node node) {
		if (node.findFirst(Node.class, n -> n.getComment().isPresent()).isPresent()) {
			// For now, Cleanthat is pretty weak on comment management (due to Javaparser limitations)
			// So we prefer aborting any modification in case of comment presence, to prevent losing comments
			// https://github.com/javaparser/javaparser/issues/3677
			LOGGER.debug("You should cancel the operation due to the presence of a comment");
			return true;
		}

		return false;
	}

	protected Optional<Node> replaceNode(Node node) {
		throw new UnsupportedOperationException("TODO Implement me in overriden classes");
	}

	public static void logJavaParserIssue(Object o, Throwable e, String issue) {
		var msg = "We encounter a case of {} for `{}`. Full-stack is available in 'debug'";
		if (LOGGER.isDebugEnabled()) {
			LOGGER.warn(msg, issue, o, e);
		} else {
			LOGGER.warn(msg, issue, o);
		}
	}

	protected Optional<ResolvedDeclaration> optResolved(Expression expr) {
		if (expr.findCompilationUnit().isEmpty()) {
			// This node is not hooked anymore on a CompilationUnit
			return Optional.empty();
		}

		if (!(expr instanceof Resolvable<?>)) {
			return Optional.empty();
		}

		try {
			Object resolved = ((Resolvable<?>) expr).resolve();
			return Optional.of((ResolvedDeclaration) resolved);
			// resolved = ((Resolvable<ResolvedValueDeclaration>) singleArgument).resolve();
		} catch (UnsolvedSymbolException e) {
			LOGGER.debug("Typically a 3rd-party symbol (e.g. in some library not loaded by CleanThat)", e);
			return Optional.empty();
		} catch (IllegalStateException | UnsupportedOperationException e) {
			if (e.getMessage().contains(SymbolResolver.class.getSimpleName())) {
				// com.github.javaparser.ast.Node.getSymbolResolver()
				LOGGER.debug("Typically a 3rd-party symbol (e.g. in some library not loaded by CleanThat)", e);
				return Optional.empty();
			} else if (e.getMessage().contains("unsolved symbol")) {
				// Caused by: java.lang.UnsupportedOperationException: CorrespondingDeclaration not available for
				// unsolved symbol.
				// at
				// com.github.javaparser.resolution.model.SymbolReference.getCorrespondingDeclaration(SymbolReference.java:116)
				LOGGER.debug("Typically a 3rd-party symbol (e.g. in some library not loaded by CleanThat)", e);
				return Optional.empty();
			} else {
				throw new IllegalStateException(e);
			}
		}
	}

	protected void onMethodName(Node node, String methodName, OnMethodName consumer) {
		if (node instanceof MethodCallExpr && methodName.equals(((MethodCallExpr) node).getName().getIdentifier())) {
			var methodCall = (MethodCallExpr) node;
			Optional<Expression> optScope = methodCall.getScope();
			if (optScope.isEmpty()) {
				// TODO Document when this would happen
				return;
			}
			var scope = optScope.get();
			Optional<ResolvedType> type = MethodCallExprHelpers.optResolvedType(scope);
			if (type.isPresent()) {
				consumer.onMethodName(methodCall, scope, type.get());
			}
		}
	}

	@Deprecated
	protected boolean scopeHasRequiredType(Optional<Expression> optScope, Class<?> requiredType) {
		return MethodCallExprHelpers.scopeHasRequiredType(optScope, requiredType);
	}

	@Deprecated
	protected boolean scopeHasRequiredType(Optional<Expression> optScope, String requiredType) {
		return MethodCallExprHelpers.scopeHasRequiredType(optScope, requiredType);
	}

	/**
	 * 
	 * @param compilationUnit
	 * @param methodRefPackage
	 * @param qualifiedName
	 * @return true if the given qualifiedName (which may be a nested Class) in given package is already imported in
	 *         given CompilationUnit
	 */
	protected boolean isImported(CompilationUnit compilationUnit, String methodRefPackage, String qualifiedName) {
		Optional<PackageDeclaration> optPackageDeclaration = compilationUnit.getPackageDeclaration();
		if (optPackageDeclaration.isPresent()) {
			var packageDecl = optPackageDeclaration.get().getNameAsString();

			// see UnnecessaryImport.removeSamePackageImports(Collection<ImportDeclaration>,
			// Optional<PackageDeclaration>)
			if (methodRefPackage.equals(packageDecl)) {
				return true;
			}
		}

		NodeList<ImportDeclaration> imports = compilationUnit.getImports();

		if (imports.isEmpty() && methodRefPackage.indexOf('.') >= 0) {
			return false;
		}

		if ("java.lang".equals(methodRefPackage)) {
			return true;
		}
		// TODO manage wildcards/asterisks
		return imports.stream().anyMatch(id -> id.getNameAsString().equals(qualifiedName));
	}

	protected String nameOrQualifiedName(CompilationUnit compilationUnit, Class<?> clazz) {
		if (isImported(compilationUnit, clazz.getPackageName(), clazz.getName())) {
			return clazz.getSimpleName();
		} else {
			return clazz.getName();
		}
	}

	/**
	 * 
	 * @param compilationUnit
	 * @param qualifiedName
	 * @return true if the given qualifiedName (which may be a nested Class) in given package can be imported in given
	 *         CompilationUnit without conflicting existing imports
	 */
	protected boolean isImportable(CompilationUnit compilationUnit, String qualifiedName) {
		NodeList<ImportDeclaration> imports = compilationUnit.getImports();

		var tokenName = getSimpleName(qualifiedName);

		// There is already a wildcard import: it may hold the token name
		return imports.stream()
				.noneMatch(id -> id.isAsterisk() || getSimpleName(id.getNameAsString()).equals(tokenName));
	}

	protected String getSimpleName(String qualifiedName) {
		var indexOfDot = qualifiedName.lastIndexOf('.');

		if (indexOfDot < 0) {
			return qualifiedName;
		} else {
			return qualifiedName.substring(indexOfDot + 1);
		}
	}

	protected boolean isMethodReturnUsed(MethodCallExpr methodCall) {
		if (!methodCall.getParentNode().isPresent()) {
			return false;
		}

		Node parentNode = methodCall.getParentNode().get();

		if (parentNode instanceof ExpressionStmt) {
			// The method is called, and nothing is done with it
			return false;
		}
		return true;
	}

}
