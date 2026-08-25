package com.rishab.scheduler.workers.handlers;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Egress policy for HttpCallbackJobHandler. Separated from WorkerProperties because this is a
// security boundary rather than a tuning knob: the values here decide what the worker is allowed
// to talk to, and grouping them under their own prefix keeps "how fast do we poll" and "can this
// job reach the cloud metadata service" from sharing a config block.
//
// Every default is the restrictive one. A deployment loosens this deliberately, in its own
// application-<profile>.yml or environment, and that loosening is then visible in configuration
// rather than buried in code.
@ConfigurationProperties(prefix = "worker.http-callback")
public record HttpCallbackProperties(

		// http is permitted alongside https because docker-compose demos and internal callbacks
		// are plain HTTP, and forcing TLS here would push people towards allowing private networks
		// instead -- a strictly worse trade. Anything outside this list (file:, ftp:, gopher:,
		// jar:, netdoc:) is rejected: those schemes are the classic SSRF escalation from "makes a
		// request" to "reads the filesystem".
		@DefaultValue({"http", "https"}) List<String> allowedSchemes,

		// The core control. False means every resolved address must be a public unicast address --
		// loopback, link-local (including 169.254.169.254, the cloud instance metadata endpoint on
		// OCI, AWS and GCP), RFC1918 private ranges, carrier-grade NAT, IPv6 unique-local, and
		// multicast are all refused. This must stay false in production: the API has no
		// authentication (README, "What this system doesn't claim"), so anyone who can submit a
		// job would otherwise have an unauthenticated request-forgery primitive pointed at
		// whatever the VM can reach -- Postgres, Redis, the metadata service, and any other
		// container on the compose network.
		//
		// Set true only for local development and tests, which necessarily call back to localhost.
		@DefaultValue("false") boolean allowPrivateNetworks,

		// Empty means "any port allowed by the checks above". Narrowing to 80/443 is available for
		// deployments that want it, and blocks using callbacks to port-scan the internal network
		// even when a host is legitimately reachable.
		@DefaultValue({}) List<Integer> allowedPorts,

		// Optional strict allow-list of destination hostnames. Empty means "any host that passes
		// the IP checks". A deployment that knows its callback targets should fill this in: it is
		// the only control here that is immune to DNS rebinding, because it is evaluated against
		// the name rather than the resolved address.
		@DefaultValue({}) List<String> allowedHosts) {
}
