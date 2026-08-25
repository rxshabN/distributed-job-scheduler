package com.rishab.scheduler.workers;

import com.rishab.scheduler.workers.handlers.HttpCallbackProperties;
import com.rishab.scheduler.workers.jobs.BackoffPolicy;
import java.io.IOException;
import java.net.HttpURLConnection;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(HttpCallbackProperties.class)
public class WorkerConfig {

	@Bean
	BackoffPolicy backoffPolicy(WorkerProperties properties) {
		return new BackoffPolicy(properties.baseBackoffDelayMillis(), properties.maxBackoffDelayMillis());
	}

	// A shared, timeout-bounded, redirect-refusing client for HttpCallbackJobHandler.
	//
	// Timeouts: without an explicit timeout a hung downstream endpoint would block the poll loop
	// indefinitely -- turning one bad callback URL into a worker that stops claiming any other
	// job. (Since the scheduler pool fix in application.yml the heartbeat still runs during that
	// block, so the worker would look alive while doing nothing, which is arguably worse than
	// being reaped.)
	//
	// Redirects: setInstanceFollowRedirects(false) is a security control, not a preference.
	// CallbackUrlValidator vets the URL the client supplied; a followed redirect is a second
	// request to an address that was never vetted, which is the standard way to walk an SSRF filter
	// -- point the callback at an endpoint you control that passes validation, then answer 302 with
	// Location: http://169.254.169.254/. Refusing to follow closes that entirely. The cost is that
	// a legitimate endpoint which 301s (http -> https, say) now fails instead of succeeding; the
	// fix for that is to submit the final URL, and the error message says so.
	// The response body is never read into memory -- the handler calls toBodilessEntity() -- and
	// the read timeout above is what bounds how long a hostile endpoint can hold the connection
	// open. There is deliberately no byte-count cap: adding one would mean replacing the request
	// factory with a body-limiting wrapper for a body this code already discards.
	@Bean
	RestClient restClient() {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory() {
			@Override
			protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
				super.prepareConnection(connection, httpMethod);
				connection.setInstanceFollowRedirects(false);
			}
		};
		requestFactory.setConnectTimeout(5_000);
		requestFactory.setReadTimeout(10_000);
		return RestClient.builder().requestFactory(requestFactory).build();
	}
}
