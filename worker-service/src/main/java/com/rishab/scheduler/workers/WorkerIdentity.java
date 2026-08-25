package com.rishab.scheduler.workers;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;
import org.springframework.stereotype.Component;

// Generated once at process startup and held for the process lifetime -- claimed_by (spec §4) and
// the Redis heartbeat key (spec §5, Weekend 3) both need a value that's stable for as long as this
// JVM is claiming jobs, but distinct across the horizontally-scaled worker instances the spec
// requires ("running N worker instances must not change correctness", spec §1). Hostname alone
// isn't enough for that uniqueness in docker-compose, where replicas can share a container name
// prefix; hostname + a random suffix is.
@Component
public class WorkerIdentity {

	private final String workerId;

	public WorkerIdentity() {
		this.workerId = resolveHostname() + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private static String resolveHostname() {
		try {
			return InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException e) {
			return "unknown-host";
		}
	}

	public String workerId() {
		return workerId;
	}
}
