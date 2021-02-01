package io.cormoran.cleanthat.rules;

import java.io.IOException;

import org.junit.Test;

import eu.solven.cleanthat.rules.OptionalNotEmpty;
import eu.solven.cleanthat.rules.cases.OptionalNotEmptyCases;
import eu.solven.cleanthat.rules.test.ATestCases;

public class TestReplaceOptionalNotEmpty extends ATestCases {

	@Test
	public void testCases() throws IOException {
		testCasesIn(OptionalNotEmptyCases.class, new OptionalNotEmpty());
	}
}
