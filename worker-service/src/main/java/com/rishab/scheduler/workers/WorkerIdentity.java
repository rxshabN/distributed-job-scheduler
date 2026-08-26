package com.rishab.scheduler.workers;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;
import org.springframework.stereotype.Component;

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
