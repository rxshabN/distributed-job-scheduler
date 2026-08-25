package com.rishab.scheduler.workers.handlers;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.stereotype.Component;

// Server-Side Request Forgery defence for HttpCallbackJobHandler (spec §8's "POSTs the payload to
// a URL"). That handler takes its destination from job payload, and the submission API has no
// authentication -- so without this, anyone who can reach POST /api/v1/jobs can make the worker
// issue arbitrary requests from inside the deployment's network. On the Oracle VM (spec §12) that
// network contains Postgres, Redis, the other containers, and the cloud instance metadata service.
//
// WHY VALIDATION IS ON THE RESOLVED IP, NOT THE URL STRING
//
// String and hostname checks are the common implementation and they do not work. "localhost" is
// only one of the spellings: 127.0.0.1, 127.1, 2130706433 (decimal), 0x7f.0.0.1 (hex), [::1],
// [::ffff:127.0.0.1] (IPv4-mapped IPv6), 0.0.0.0, and any attacker-controlled DNS name with an A
// record pointing at 127.0.0.1 all reach the same place. Resolving the name and inspecting the
// actual InetAddress collapses every one of those spellings into the same check, because they all
// resolve to an address the JDK will classify identically. That is why this class calls
// InetAddress.getAllByName rather than pattern-matching the URI.
//
// ALL resolved addresses are checked, not just the first: a hostname with both a public and a
// private A record would otherwise pass validation and then connect to whichever the resolver
// handed the HTTP client.
//
// KNOWN LIMITATION -- DNS REBINDING, STATED RATHER THAN PAPERED OVER
//
// This validates the name, then the HTTP client resolves it again to make the request. An attacker
// controlling the authoritative DNS for a name can answer the first lookup with a public address
// and the second with 169.254.169.254 -- a TOCTOU window this design cannot close, because
// RestClient offers no hook to pin a validated address for the connection while preserving TLS
// SNI and certificate validation. Closing it properly means either a custom connection-level
// resolver (real complexity, and easy to get subtly wrong for HTTPS) or -- the honest production
// answer -- network-level egress control on the VM, which is enforcement the application cannot
// be trusted to do for itself. allowedHosts is the in-app mitigation: a non-empty allow-list is
// evaluated on the name and is therefore immune to rebinding, since a rebound address still has to
// arrive under an allow-listed hostname.
//
// Recommended for the deployed system: iptables/nftables egress rules on the VM denying the
// worker containers access to 169.254.0.0/16 and the compose subnet, with this validator as the
// defence-in-depth layer that also produces a clear error message.
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
