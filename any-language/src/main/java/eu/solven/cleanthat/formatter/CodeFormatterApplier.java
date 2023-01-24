/*
 * Copyright 2023 Solven
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
package eu.solven.cleanthat.formatter;

import eu.solven.cleanthat.config.IncludeExcludeHelpers;
import eu.solven.cleanthat.engine.EnginePropertiesAndBuildProcessors;
import eu.solven.cleanthat.engine.ICodeFormatterApplier;
import eu.solven.cleanthat.language.IEngineProperties;
import eu.solven.cleanthat.language.ISourceCodeProperties;
import java.io.IOException;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract class for language formatters
 *
 * @author Benoit Lacelle
 */
public class CodeFormatterApplier implements ICodeFormatterApplier {
	private static final Logger LOGGER = LoggerFactory.getLogger(CodeFormatterApplier.class);

	public static final AtomicInteger NB_EXCEPTIONS = new AtomicInteger();

	@Override
	public String applyProcessors(EnginePropertiesAndBuildProcessors engineAndSteps, String filepath, String code)
			throws IOException {
		AtomicReference<String> outputRef = new AtomicReference<>(code);

		engineAndSteps.getLinters().forEach(step -> {
			try {
				String input = outputRef.get();
				IEngineProperties engineProperties = step.getKey();
				ILintFixer lintFixer = step.getValue();
				String output = applyProcessor(engineProperties, lintFixer, filepath, input);
				if (output == null) {
					throw new IllegalStateException("Null code. TODO");
				}
				if (!input.equals(output)) {
					// Beware each processor may change a file, but the combined changes leads to a no change (e.g. the
					// final formatting step may clean all previous not relevant changes)
					LOGGER.debug("Mutated a file given: {}", step);
					outputRef.set(output);
				}
			} catch (IOException | RuntimeException e) {
				NB_EXCEPTIONS.incrementAndGet();
				// Log and move to next processor
				LOGGER.warn(
						"Issue over file='" + filepath
								+ "' with processor="
								+ step
								+ ". Please report it to: "
								+ "https://github.com/solven-eu/cleanthat/issues",
						e);
			}
		});
		return outputRef.get();
	}

	protected String applyProcessor(IEngineProperties languageProperties,
			ILintFixer formatter,
			String filepath,
			String code) throws IOException {
		Objects.requireNonNull(code, "code should not be null");
		ISourceCodeProperties sourceCodeProperties = languageProperties.getSourceCode();

		// TODO We should skip excluded files BEFORE loading their content
		List<PathMatcher> includeMatchers = IncludeExcludeHelpers.prepareMatcher(sourceCodeProperties.getIncludes());
		Optional<PathMatcher> matchingInclude = IncludeExcludeHelpers.findMatching(includeMatchers, filepath);
		if (matchingInclude.isEmpty()) {
			LOGGER.debug("File {} was initially included but not included for processor: {}", filepath, formatter);
			return code;
		}

		List<PathMatcher> excludeMatchers = IncludeExcludeHelpers.prepareMatcher(sourceCodeProperties.getExcludes());
		Optional<PathMatcher> matchingExclude = IncludeExcludeHelpers.findMatching(excludeMatchers, filepath);
		if (matchingExclude.isPresent()) {
			LOGGER.debug("File {} was initially not-excluded but excluded for processor: {}", filepath, formatter);
			return code;
		}
		LineEnding lineEnding = languageProperties.getSourceCode().getLineEndingAsEnum();

		if (lineEnding == LineEnding.GIT) {
			// see GitAttributesLineEndings_InMemory
			LOGGER.warn("We switch lineEnding from {} to {}", lineEnding, LineEnding.NATIVE);
			lineEnding = LineEnding.NATIVE;
		} else if (lineEnding == LineEnding.KEEP) {
			lineEnding = LineEnding.determineLineEnding(code).orElse(LineEnding.NATIVE);
		}

		return formatter.doFormat(code, lineEnding);
	}
}
