package eu.solven.cleanthat.rules;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import cormoran.pepper.logging.PepperLogHelper;
import eu.solven.cleanthat.rules.meta.IClassTransformer;

// see https://jsparrow.github.io/rules/enums-without-equals.html#properties
@Deprecated(since = "Not-Ready: how can we infer a Type is an Enum?")
public class EnumsWithoutEquals implements IClassTransformer {
	private static final Logger LOGGER = LoggerFactory.getLogger(EnumsWithoutEquals.class);

	public String minimalJavaVersion() {
		return "5";
	}

	public void transform(MethodDeclaration pre) {
		// https://stackoverflow.com/questions/55309460/how-to-replace-expression-by-string-in-javaparser-ast
		pre.walk(node -> {
			LOGGER.info("{}", PepperLogHelper.getObjectAndClass(node));

			if (node instanceof MethodCallExpr && "equals".equals(((MethodCallExpr) node).getName().getIdentifier())) {
				MethodCallExpr methodCall = ((MethodCallExpr) node);
				Optional<Expression> optScope = methodCall.getScope();
				if (!optScope.isPresent()) {
					// TODO Document when this would happen
					return;
				}
				Expression scope = optScope.get();

				CombinedTypeSolver ts = new CombinedTypeSolver();
				ts.add(new ReflectionTypeSolver());
				ResolvedType type = JavaParserFacade.get(ts).getType(scope);

				if (type.isReferenceType()) {
					LOGGER.info("scope={} type={}", scope, type);
				}
			}
		});
	}
}
