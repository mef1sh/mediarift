package com.mef1sh.mediarift.stream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Small helpers for starting and stopping programs. */
final class ProcUtil {
	private ProcUtil() {
	}

	/** Runs a program, returns what it printed (stdout + stderr) or null if it did not finish / could not start. */
	static String capture(List<String> cmd, int timeoutSeconds) {
		Process p = null;
		try {
			ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.redirectErrorStream(true);
			p = pb.start();
			p.getOutputStream().close();
			final InputStream in = p.getInputStream();
			final ByteArrayOutputStream buf = new ByteArrayOutputStream();
			Thread reader = new Thread(() -> {
				try {
					byte[] b = new byte[8192];
					int n;
					while ((n = in.read(b)) > 0) {
						synchronized (buf) {
							if (buf.size() < 4_000_000) buf.write(b, 0, n);
						}
					}
				} catch (IOException ignored) {
				}
			}, "MediaRift-capture");
			reader.setDaemon(true);
			reader.start();
			if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
				destroyTree(p);
				return null;
			}
			reader.join(2000);
			synchronized (buf) {
				return buf.toString(StandardCharsets.UTF_8);
			}
		} catch (Exception e) {
			return null;
		} finally {
			if (p != null && p.isAlive()) destroyTree(p);
		}
	}

	/** Stops a program AND the programs it started (the streamlink launcher starts python as a child). */
	static void destroyTree(Process p) {
		if (p == null) return;
		try {
			p.descendants().forEach(ProcessHandle::destroyForcibly);
		} catch (Throwable ignored) {
		}
		p.destroyForcibly();
	}
}
