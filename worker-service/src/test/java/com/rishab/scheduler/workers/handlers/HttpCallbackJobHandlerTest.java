package com.rishab.scheduler.workers.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rishab.scheduler.workers.jobs.JobContext;
import com.rishab.scheduler.workers.jobs.JobExecutionException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

// Uses a plain JDK HttpServer as the callback target rather than a mocking library (none is a
// project dependency) -- real bytes over a real socket, which is the point of this handler
// existing at all (spec §8: "demonstrates real I/O failure").
class HttpCallbackJobHandlerTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	// allow-private-networks is enabled for this handler test specifically because its whole point
	// is a real request over a real socket to a JDK HttpServer on localhost -- a loopback address,
	// which the production default (false) exists to refuse. Production wiring (WorkerConfig)
	// leaves that default in place; it is loosened only here so the existing I/O-behaviour tests
	// below can still reach their local server.
	private final HttpCallbackJobHandler handler =
			new HttpCallbackJobHandler(restClient(), permissiveValidator());
	private HttpServer server;

	private static CallbackUrlValidator permissiveValidator() {
		return new CallbackUrlValidator(new HttpCallbackProperties(
				List.of("http", "https"), true, List.of(), List.of()));
	}

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	void postsPayloadBodyToUrl() throws Exception {
		AtomicReference<String> receivedBody = new AtomicReference<>();
		server = startServer(200, receivedBody);
		String url = "http://localhost:" + server.getAddress().getPort() + "/callback";

		JobContext ctx = context(Map.of("url", url, "body", Map.of("hello", "world")));
		handler.execute(ctx);

		assertThat(receivedBody.get()).contains("\"hello\"").contains("\"world\"");
	}

	@Test
	void throwsJobExecutionExceptionOnNon2xxResponse() throws Exception {
		server = startServer(500, new AtomicReference<>());
		String url = "http://localhost:" + server.getAddress().getPort() + "/callback";

		JobContext ctx = context(Map.of("url", url, "body", Map.of()));

		assertThatThrownBy(() -> handler.execute(ctx)).isInstanceOf(JobExecutionException.class);
	}

	@Test
	void throwsJobExecutionExceptionWhenUrlIsMissing() {
		JobContext ctx = context(Map.of("body", Map.of()));

		assertThatThrownBy(() -> handler.execute(ctx)).isInstanceOf(JobExecutionException.class);
	}

	@Test
	void throwsJobExecutionExceptionWhenHostIsUnreachable() {
		JobContext ctx = context(Map.of("url", "http://localhost:1", "body", Map.of()));

		assertThatThrownBy(() -> handler.execute(ctx)).isInstanceOf(JobExecutionException.class);
	}

	private JobContext context(Map<String, ?> payload) {
		return new JobContext(1L, "http-callback", objectMapper.valueToTree(payload), 1, "test-worker");
	}

	private static HttpServer startServer(int status, AtomicReference<String> receivedBody) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/callback", exchange -> {
			byte[] body = exchange.getRequestBody().readAllBytes();
			receivedBody.set(new String(body));
			exchange.sendResponseHeaders(status, -1);
			exchange.close();
		});
		server.start();
		return server;
	}

	private static RestClient restClient() {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(2_000);
		requestFactory.setReadTimeout(2_000);
		return RestClient.builder().requestFactory(requestFactory).build();
	}
}
