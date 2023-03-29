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

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import com.google.common.io.ByteStreams;

import eu.solven.cleanthat.engine.java.refactorer.mutators.LiteralsFirstInComparisons;
import eu.solven.cleanthat.engine.java.refactorer.test.AJavaparserTestCases;

public class TestOptionalNotEmptyCustom extends AJavaparserTestCases {
	final LiteralsFirstInComparisons mutator = new LiteralsFirstInComparisons();

	@Test
	public void testNotIdempotent() throws IOException {
		Resource testRoaringBitmapSource =
				new ClassPathResource("/source/do_not_format_me/Spotless/testCaseOptionalNotEmpty.java");
		var asString =
				new String(ByteStreams.toByteArray(testRoaringBitmapSource.getInputStream()), StandardCharsets.UTF_8);

		var compilationUnit = parseCompilationUnit(mutator, asString);

		var transformed = mutator.walkAstHasChanged(compilationUnit);

		Assertions.assertThat(transformed).isTrue();
		Assertions.assertThat(mutator.getNbIdempotencyIssues()).isEqualTo(0);
	}
}