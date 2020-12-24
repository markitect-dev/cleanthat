package io.cormoran.cleanthat.local;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.kohsuke.github.GHBranch;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRef;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Suppliers;
import com.nimbusds.jose.JOSEException;

import eu.solven.cleanthat.formatter.eclipse.JavaFormatter;
import eu.solven.cleanthat.github.event.CommitContext;
import eu.solven.cleanthat.github.event.GithubPullRequestCleaner;
import eu.solven.cleanthat.github.event.GithubWebhookHandlerFactory;
import eu.solven.cleanthat.github.event.IGithubWebhookHandler;
import eu.solven.cleanthat.lambda.CleanThatLambdaFunction;

@SpringBootApplication(scanBasePackages = "none")
public class RunCleanGithubPR extends CleanThatLambdaFunction {

	private static final Logger LOGGER = LoggerFactory.getLogger(RunCleanGithubPR.class);

	private static final String SOLVEN_EU_MITRUST_DATASHARING = "solven-eu/mitrust-datasharing";

	private static final String SOLVEN_EU_CLEANTHAT = "solven-eu/cleanthat";

	private static final String SOLVEN_EU_AGILEA = "solven-eu/agilea";

	private static final String SOLVEN_EU_SPRING_BOOT = "solven-eu/spring-boot";

	final int githubInstallationId = 9086720;

	final String repoFullName = SOLVEN_EU_SPRING_BOOT;

	public static void main(String[] args) {
		SpringApplication.run(RunCleanGithubPR.class, args);
	}

	@EventListener(ContextRefreshedEvent.class)
	public void doSomethingAfterStartup(ContextRefreshedEvent event) throws IOException, JOSEException {
		ApplicationContext appContext = event.getApplicationContext();
		GithubWebhookHandlerFactory factory = appContext.getBean(GithubWebhookHandlerFactory.class);
		IGithubWebhookHandler handler = factory.makeWithFreshJwt();
		GitHub github = handler.makeInstallationGithub(githubInstallationId);
		ObjectMapper objectMapper = new ObjectMapper();
		GithubPullRequestCleaner cleaner = new GithubPullRequestCleaner(objectMapper, new JavaFormatter(objectMapper));
		GHRepository repo;
		try {
			repo = github.getRepository(repoFullName);
		} catch (GHFileNotFoundException e) {
			LOGGER.error("Either the repository is private, or it does not exist: '{}'", repoFullName);
			return;
		}
		LOGGER.info("Repository name={} id={}", repo.getName(), repo.getId());
		String defaultBranchName = Optional.ofNullable(repo.getDefaultBranch()).orElse("master");
		GHBranch defaultBranch;
		try {
			defaultBranch = repo.getBranch(defaultBranchName);
		} catch (GHFileNotFoundException e) {
			throw new IllegalStateException("We can not find as default branch: " + defaultBranchName, e);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		Optional<Map<String, ?>> mainBranchConfig = cleaner.branchConfig(defaultBranch);

		if (mainBranchConfig.isEmpty()) {
			LOGGER.info("CleanThat is not configured in the main branch ({})", defaultBranch.getName());

			Optional<GHBranch> branchWithConfig =
					repo.getBranches().values().stream().filter(b -> cleaner.branchConfig(b).isPresent()).findAny();
			boolean configExistsAnywhere = branchWithConfig.isPresent();
			if (!configExistsAnywhere) {
				// At some point, we could prefer remaining silent if we understand the repository tried to integrate
				// us, but did not completed.
				cleaner.openPRWithCleanThatStandardConfiguration(defaultBranch);
			} else {
				LOGGER.info("There is at least one branch with CleanThat configured ({})",
						branchWithConfig.get().getName());
			}
		} else {
			LOGGER.info("CleanThat is configured in the main branch ({})", defaultBranch.getName());

			AtomicReference<GHPullRequest> createdPr = new AtomicReference<>();

			Map<String, ?> output = cleaner.formatPR(new CommitContext(false, false), Suppliers.memoize(() -> {
				GHPullRequest pr = makePR(repo, defaultBranch);
				createdPr.set(pr);
				return pr;
			}));

			if (createdPr.get() == null) {
				LOGGER.info("Not a single file has been impacted");
			} else {
				LOGGER.info("Created PR: {}", createdPr.get().getHtmlUrl().toExternalForm());
				LOGGER.info("Details: {}", output);
			}
		}
	}

	private GHPullRequest makePR(GHRepository repo, GHBranch base) {
		String cleanThatPrId = UUID.randomUUID().toString();
		try {
			GHRef ref = repo.createRef("CleanThat_" + cleanThatPrId, base.getSHA1());
			return repo.createPullRequest("CleanThat - Cleaning style - "
					+ cleanThatPrId, ref.getRef(), base.getName(), SOLVEN_EU_AGILEA, false, true);
		} catch (IOException e) {
			throw new UncheckedIOException("Issue opening PR", e);
		}

	}
}
