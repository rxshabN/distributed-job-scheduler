package com.rishab.scheduler.workers.handlers;

import com.rishab.scheduler.workers.jobs.JobContext;
import com.rishab.scheduler.workers.jobs.JobExecutionException;
import com.rishab.scheduler.workers.jobs.JobHandler;
import java.net.URI;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

@Component
public class HttpCallbackJobHandler implements JobHandler {

	private final RestClient restClient;
	private final CallbackUrlValidator callbackUrlValidator;

	public HttpCallbackJobHandler(RestClient restClient, CallbackUrlValidator callbackUrlValidator) {
		this.restClient = restClient;
		this.callbackUrlValidator = callbackUrlValidator;
	}

	@Override
	public String jobType() {
		return "http-callback";
	}

	@Override
	public void execute(JobContext ctx) throws JobExecutionException {
		JsonNode payload = ctx.payload();
		JsonNode urlNode = payload.get("url");
		if (urlNode == null || urlNode.asString().isBlank()) {
			throw new JobExecutionException("http-callback payload missing required 'url' field");
		}
		String rawUrl = urlNode.asString();
		URI url = callbackUrlValidator.validate(rawUrl);
		JsonNode body = payload.get("body");

		try {
			HttpStatusCode status = restClient.post()
					.uri(url)
					.body(body != null ? body : JsonNodeFactory.instance.objectNode())
					.retrieve()
					.toBodilessEntity()
					.getStatusCode();
			if (status.is3xxRedirection()) {
				throw new JobExecutionException(
						"http-callback to '%s' returned a redirect (%s); redirects are not followed, because the "
								.formatted(rawUrl, status)
								+ "redirect target bypasses the egress validation applied to the original URL");
			}
		} catch (RestClientException ex) {
			throw new JobExecutionException("http-callback to '%s' failed: %s".formatted(rawUrl, ex.getMessage()), ex);
		}
	}
}
