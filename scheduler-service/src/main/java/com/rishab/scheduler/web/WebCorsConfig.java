package com.rishab.scheduler.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// Spec §12: the dashboard (Vercel) and this API (Oracle VM) are cross-origin by definition, and
// Spring Security is explicitly out of scope for this project -- this WebMvcConfigurer mapping is
// the whole CORS story. allowedOriginPatterns rather than allowedOrigins so both the fixed
// production domain and Vercel's per-deploy preview subdomains (*.vercel.app) work without
// updating this list on every deploy.
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

	private final String[] allowedOriginPatterns;

	public WebCorsConfig(@Value("${dashboard.allowed-origin-patterns:http://localhost:3000}") String[] allowedOriginPatterns) {
		this.allowedOriginPatterns = allowedOriginPatterns;
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/api/**")
				.allowedOriginPatterns(allowedOriginPatterns)
				.allowedMethods("GET", "POST")
				.allowedHeaders("Content-Type");
	}
}
