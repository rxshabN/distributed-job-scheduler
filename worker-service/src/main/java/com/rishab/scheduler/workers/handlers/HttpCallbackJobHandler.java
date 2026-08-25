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

// Spec §8 handler 1: POSTs the payload to a URL. Naturally retryable (a flaky downstream endpoint
// is exactly the kind of failure spec §6's backoff exists for) and demonstrates real I/O failure
// rather than a simulated one. Payload shape: {"url": "...", "body": {...}} -- "body" is what gets
// POSTed, "url" is where.
//
// This is the only place in the system that makes an outbound request to an address a *client*
// chose, which makes it the system's SSRF surface. Two controls, applied in order:
//
//  1. CallbackUrlValidator checks the destination against the deployment's egress policy before
//     any connection is attempted. See that class for why the check is on resolved IPs.
//  2. Redirects are refused rather than followed. This matters as much as (1): validation applies
//     to the URL the client supplied, so an endpoint that passes validation and then answers 302
//     Location: http://169.254.169.254/... would walk straight past it if the client followed the
//     hop. WorkerConfig disables following at the request-factory level, and the 3xx check below
//     turns the un-followed redirect into an explicit failure instead of a silently "successful"
//     job that never delivered its payload.
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
		// Throws CallbackUrlNotAllowedException (a JobExecutionException) if the destination is
		// outside the egress policy, so a blocked callback is recorded as an ordinary job failure
		// with the reason attached.
		URI url = callbackUrlValidator.validate(rawUrl);
		JsonNode body = payload.get("body");

		try {
			HttpStatusCode status = restClient.post()
					.uri(url)
					.body(body != null ? body : JsonNodeFactory.instance.objectNode())
					.retrieve()
					// The default error handler already turns 4xx/5xx into an exception; this
					// exists for 3xx, which it treats as success because a redirect-following
					// client would normally have resolved it before now.
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
