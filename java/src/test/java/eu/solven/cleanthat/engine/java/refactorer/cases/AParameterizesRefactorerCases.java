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
package eu.solven.cleanthat.engine.java.refactorer.cases;

import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import eu.solven.cleanthat.engine.java.refactorer.meta.IJavaparserMutator;
import eu.solven.cleanthat.engine.java.refactorer.test.AParentRefactorerCases;

@RunWith(Parameterized.class)
public abstract class AParameterizesRefactorerCases extends AParameterizesRefactorerCases2<Node, Node> {

	public AParameterizesRefactorerCases(JavaParser javaParser, String testName, ClassOrInterfaceDeclaration testCase) {
		super(javaParser, testName, testCase);
	}

	protected abstract AParentRefactorerCases<Node, Node, IJavaparserMutator> getCases();

	@Override
	protected Node convertToAst(Node node) {
		return node;
	}
}
