package com.rishab.scheduler.workers.handlers;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "worker.http-callback")
public record HttpCallbackProperties(
		@DefaultValue({"http", "https"}) List<String> allowedSchemes,
		@DefaultValue("false") boolean allowPrivateNetworks,
		@DefaultValue({}) List<Integer> allowedPorts,
		@DefaultValue({}) List<String> allowedHosts) {
}
