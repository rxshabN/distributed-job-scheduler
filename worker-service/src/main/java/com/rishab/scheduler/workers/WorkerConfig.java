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
