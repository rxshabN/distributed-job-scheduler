package com.rishab.scheduler.workers.handlers;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class CallbackUrlValidator {

	private final HttpCallbackProperties properties;

	public CallbackUrlValidator(HttpCallbackProperties properties) {
		this.properties = properties;
	}

	/**
	 * @throws CallbackUrlNotAllowedException if the URL is malformed or its destination is outside
	 *         the configured egress policy. Callers surface this as a job failure.
	 */
	public URI validate(String rawUrl) throws CallbackUrlNotAllowedException {
		URI uri = parse(rawUrl);

		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (properties.allowedSchemes().stream().noneMatch(allowed -> allowed.equalsIgnoreCase(scheme))) {
			throw new CallbackUrlNotAllowedException(
					"scheme '%s' is not allowed (permitted: %s)".formatted(scheme, properties.allowedSchemes()));
		}

		String host = uri.getHost();
		if (host == null || host.isBlank()) {
			// Notably the case for "http:///etc/passwd" and for authority forms URI cannot parse a
			// host out of -- both must be refused rather than handed to the HTTP client to
			// interpret.
			throw new CallbackUrlNotAllowedException("url has no resolvable host: " + rawUrl);
		}

		if (!properties.allowedHosts().isEmpty()
				&& properties.allowedHosts().stream().noneMatch(allowed -> allowed.equalsIgnoreCase(host))) {
			throw new CallbackUrlNotAllowedException(
					"host '%s' is not in the configured allow-list".formatted(host));
		}

		int port = uri.getPort() == -1 ? defaultPortFor(scheme) : uri.getPort();
		if (!properties.allowedPorts().isEmpty() && !properties.allowedPorts().contains(port)) {
			throw new CallbackUrlNotAllowedException(
					"port %d is not allowed (permitted: %s)".formatted(port, properties.allowedPorts()));
		}

		if (!properties.allowPrivateNetworks()) {
			requireAllAddressesPublic(host);
		}
		return uri;
	}

	private static URI parse(String rawUrl) throws CallbackUrlNotAllowedException {
		try {
			return new URI(rawUrl);
		} catch (URISyntaxException e) {
			throw new CallbackUrlNotAllowedException("url is not a valid URI: " + rawUrl, e);
		}
	}

	private void requireAllAddressesPublic(String host) throws CallbackUrlNotAllowedException {
		InetAddress[] addresses;
		try {
			addresses = InetAddress.getAllByName(host);
		} catch (UnknownHostException e) {
			// Refused rather than deferred to the HTTP client: a name that does not resolve now
			// cannot be validated now, and letting the request proceed would mean the only
			// resolution that mattered was the unvalidated one.
			throw new CallbackUrlNotAllowedException("host '%s' could not be resolved".formatted(host), e);
		}
		for (InetAddress address : addresses) {
			String reason = disallowedReason(address);
			if (reason != null) {
				throw new CallbackUrlNotAllowedException(
						("host '%s' resolves to %s, which is %s; set "
								+ "worker.http-callback.allow-private-networks=true only for local development")
								.formatted(host, address.getHostAddress(), reason));
			}
		}
	}

	// Returns why the address is refused, or null if it is an acceptable public destination.
	// Ordered roughly by how commonly each is the actual attack target.
	private static String disallowedReason(InetAddress address) {
		if (address.isLoopbackAddress()) {
			return "a loopback address";
		}
		if (address.isLinkLocalAddress()) {
			// 169.254.0.0/16 and fe80::/10. This is the cloud instance metadata service on OCI,
			// AWS and GCP -- the single highest-value SSRF target on any cloud VM, because it
			// hands out credentials to anything that can issue a plain HTTP GET.
			return "a link-local address (this covers the cloud instance metadata service)";
		}
		if (address.isSiteLocalAddress()) {
			// 10/8, 172.16/12, 192.168/16 -- and the docker-compose network the other containers
			// are on.
			return "a private/site-local address";
		}
		if (address.isAnyLocalAddress()) {
			return "a wildcard address";
		}
		if (address.isMulticastAddress()) {
			return "a multicast address";
		}
		byte[] octets = address.getAddress();
		if (octets.length == 4) {
			int first = octets[0] & 0xFF;
			int second = octets[1] & 0xFF;
			// isSiteLocalAddress misses these two, and both route somewhere real.
			if (first == 100 && second >= 64 && second <= 127) {
				return "carrier-grade NAT space (100.64.0.0/10)";
			}
			if (first == 0) {
				return "reserved space (0.0.0.0/8)";
			}
		} else if (octets.length == 16 && (octets[0] & 0xFE) == 0xFC) {
			// fc00::/7. Java has no isUniqueLocalAddress, so this is checked by prefix.
			return "an IPv6 unique-local address (fc00::/7)";
		}
		return null;
	}

	private static int defaultPortFor(String scheme) {
		return "https".equals(scheme) ? 443 : 80;
	}
}
