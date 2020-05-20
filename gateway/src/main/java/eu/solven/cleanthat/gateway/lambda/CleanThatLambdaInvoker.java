package eu.solven.cleanthat.gateway.lambda;

import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.lambda.AWSLambdaAsync;
import com.amazonaws.services.lambda.AWSLambdaAsyncClient;
import com.amazonaws.services.lambda.model.InvokeRequest;
import com.amazonaws.services.lambda.model.InvokeResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.Futures;

/**
 * Invoke AWS Lambda
 * 
 * @author Benoit Lacelle
 *
 */
// https://aws.amazon.com/fr/blogs/developer/invoking-aws-lambda-functions-from-java/
public class CleanThatLambdaInvoker {
	private static final Logger LOGGER = LoggerFactory.getLogger(CleanThatLambdaInvoker.class);

	final Environment env;
	final ObjectMapper objectMapper;

	public CleanThatLambdaInvoker(Environment env, ObjectMapper objectMapper) {
		this.env = env;
		this.objectMapper = objectMapper;
	}

	public void runWithPayload(String region, String functionName, Object payload) {

		InvokeRequest request = new InvokeRequest();
		try {
			request.withFunctionName(functionName).withPayload(objectMapper.writeValueAsString(payload));
		} catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}

		AWSLambdaAsync client = makeClient(region);

		LOGGER.info("Invoking " + functionName + ": " + request);
		Future<InvokeResult> future = client.invokeAsync(request);
		LOGGER.info("Invoked " + functionName + ": " + request);
		InvokeResult result = Futures.getUnchecked(future);
		LOGGER.info("Completed " + functionName + ": " + result);
	}

	private AWSLambdaAsync makeClient(String region) {
		BasicAWSCredentials credentials =
				new BasicAWSCredentials(env.getRequiredProperty("aws.lambda.invoke.access-key"),
						env.getRequiredProperty("aws.lambda.invoke.secret-key"));
		AWSLambdaAsync client = AWSLambdaAsyncClient.asyncBuilder()
				.withRegion(Regions.fromName(region))
				.withCredentials(new AWSStaticCredentialsProvider(credentials))
				.build();
		return client;
	}
}
