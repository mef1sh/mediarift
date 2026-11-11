package com.mef1sh.mediarift.stream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plays live streams (H.264 / AAC) in a browser that cannot decode them.
 *
 *   streamlink  ->  ffmpeg (H.264 -> VP9, AAC -> Opus, WebM)  ->  local HTTP server (127.0.0.1)  ->  <video> in the player window
 *
 * No Minecraft code in here on purpose.
 */
public final class LiveStreamService {
	private static final LiveStreamService INSTANCE = new LiveStreamService();

	public static LiveStreamService get() {
		return INSTANCE;
	}

	/** Where messages for the log go (the mod sets this to its logger). */
	public static volatile Consumer<String> log = s -> System.err.println("[live] " + s);

	private static final long START_TIMEOUT_SECONDS = 45;
	private static final long WRITE_STALL_SECONDS = 60;

	private final String token;
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "MediaRift-LiveWatchdog");
		t.setDaemon(true);
		return t;
	});

	private ServerSocket server;
	private int port;
	private Session session;
	private Pipeline pipeline;
	private byte[] playerHtml;

	private LiveStreamService() {
		byte[] b = new byte[12];
		new SecureRandom().nextBytes(b);
		StringBuilder sb = new StringBuilder();
		for (byte x : b) sb.append(String.format("%02x", x));
		token = sb.toString();
		Runtime.getRuntime().addShutdownHook(new Thread(this::stopPipeline, "MediaRift-LiveShutdown"));
	}

	/** What we are playing right now (a new one for every /sb live or auto start). */
	private static final class Session {
		String pageUrl, title;
		/** true = a normal video with an end (not a live stream): no jumping to the live edge, no reconnecting after a pause. */
		boolean vod;
		StreamOptions opts;
		Path ffmpeg, streamlink;
		volatile String state = "starting";   // starting | live | error | ended | idle
		volatile String message = "";
	}

	// ----------------------------------------------------------------- public

	/**
	 * Gets everything ready for one stream.
	 *
	 * @param vod true = a normal video with an end (Twitch / Kick video, Vimeo ...): it is converted at normal speed, no live-edge jumping
	 * @return the local address to open in the browser (nothing is started yet: the page asks for the video itself)
	 */
	public synchronized String prepare(String pageUrl, String title, boolean vod, StreamOptions o, Path toolsDir) throws StreamException {
		String url = pageUrl == null ? "" : pageUrl.trim();
		if (!(url.startsWith("https://") || url.startsWith("http://"))) throw new StreamException("That is not a web address: " + pageUrl);

		Path ff = ToolLocator.find("ffmpeg", o.ffmpegPath, toolsDir);
		Path sl = ToolLocator.find("streamlink", o.streamlinkPath, toolsDir);
		if (ff == null || sl == null) {
			String missing = ff == null && sl == null ? "ffmpeg and streamlink" : (ff == null ? "ffmpeg" : "streamlink");
			throw new StreamException("Live streams need " + missing + ". Type /sb install to download it "
					+ "(or install it yourself and restart the game).");
		}
		if (!ToolLocator.hasEncoders(ff)) {
			throw new StreamException("This ffmpeg (" + ff + ") cannot write VP9 / Opus. Use a full build, or type /sb install.");
		}
		ensureServer();
		stopPipeline();

		Session s = new Session();
		s.pageUrl = url;
		s.vod = vod;
		s.title = (title == null || title.isBlank()) ? "Live stream" : title.strip();
		s.opts = o;
		s.ffmpeg = ff;
		s.streamlink = sl;
		session = s;
		log.accept("prepared " + url);
		return localUrl(s);
	}

	private String localUrl(Session s) {
		return "http://127.0.0.1:" + port + "/" + token + "/player.html?t=" + URLEncoder.encode(s.title, StandardCharsets.UTF_8);
	}

	/** true if this address is our own local player page. */
	public boolean isLocalUrl(String url) {
		return url != null && port != 0 && url.startsWith("http://127.0.0.1:" + port + "/" + token + "/");
	}

	/** Stops ffmpeg / streamlink (the local server stays, it costs nothing). */
	public void stopPipeline() {
		Pipeline p;
		synchronized (this) {
			p = pipeline;
			pipeline = null;
		}
		if (p != null) p.kill();
	}

	/** A few lines for the player: which programs were found and how the stream is doing. */
	public String describe(StreamOptions o, Path toolsDir) {
		StringBuilder sb = new StringBuilder();
		Path ff = ToolLocator.find("ffmpeg", o.ffmpegPath, toolsDir);
		Path sl = ToolLocator.find("streamlink", o.streamlinkPath, toolsDir);
		if (ff == null) sb.append("ffmpeg: NOT FOUND\n");
		else {
			String v = ToolLocator.version(ff, "-version");
			sb.append("ffmpeg: ").append(ff).append(v == null ? "  (does not start!)" : "  (" + v + ")")
					.append(v != null && !ToolLocator.hasEncoders(ff) ? "  NO VP9/Opus ENCODER!" : "").append('\n');
		}
		if (sl == null) sb.append("streamlink: NOT FOUND\n");
		else {
			String v = ToolLocator.version(sl, "--version");
			sb.append("streamlink: ").append(sl).append(v == null ? "  (does not start!)" : "  (" + v + ")").append('\n');
		}
		Pipeline p = pipeline;
		Session s = session;
		if (p != null && !p.killed && s != null) {
			sb.append("Now: ").append(s.state).append(", ").append(s.pageUrl);
			if (p.speed > 0) sb.append(String.format(Locale.ROOT, ", encoder speed %.2fx", p.speed));
		} else {
			sb.append("No stream is running.");
		}
		if (ff == null || sl == null) sb.append("\nType /sb install to download what is missing.");
		return sb.toString();
	}

	// ----------------------------------------------------------------- server

	private void ensureServer() throws StreamException {
		if (server != null && !server.isClosed()) return;
		try {
			server = new ServerSocket(0, 16, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
		} catch (IOException e) {
			throw new StreamException("Could not start the local stream server: " + e.getMessage());
		}
		port = server.getLocalPort();
		final ServerSocket ss = server;
		Thread t = new Thread(() -> {
			while (!ss.isClosed()) {
				try {
					Socket c = ss.accept();
					Thread h = new Thread(() -> handle(c), "MediaRift-LiveClient");
					h.setDaemon(true);
					h.start();
				} catch (IOException e) {
					if (ss.isClosed()) return;
				}
			}
		}, "MediaRift-LiveServer");
		t.setDaemon(true);
		t.start();
		log.accept("local stream server on 127.0.0.1:" + port);
	}

	private void handle(Socket s) {
		try (s) {
			s.setSoTimeout(10_000);
			s.setTcpNoDelay(true);
			String head = readHead(s.getInputStream());
			if (head == null) return;
			String[] first = head.split("\r\n", 2)[0].split(" ");
			if (first.length < 2) return;
			String method = first[0], target = first[1];
			int q = target.indexOf('?');
			String path = q >= 0 ? target.substring(0, q) : target;
			String prefix = "/" + token + "/";
			OutputStream out = s.getOutputStream();
			if (!path.startsWith(prefix)) {
				respond(out, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8), false);
				return;
			}
			if (!method.equals("GET") && !method.equals("HEAD")) {
				respond(out, 405, "text/plain; charset=utf-8", "Method not allowed".getBytes(StandardCharsets.UTF_8), false);
				return;
			}
			boolean headOnly = method.equals("HEAD");
			switch (path.substring(prefix.length())) {
				case "player.html" -> respond(out, 200, "text/html; charset=utf-8", playerHtml(), headOnly);
				case "status" -> respond(out, 200, "application/json; charset=utf-8", statusJson().getBytes(StandardCharsets.UTF_8), headOnly);
				case "live.webm" -> {
					if (headOnly) respond(out, 200, "video/webm", new byte[0], true);
					else streamVideo(s, out);
				}
				default -> respond(out, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8), headOnly);
			}
		} catch (IOException ignored) {
			// the browser went away: normal
		} catch (Throwable t) {
			log.accept("client error: " + t);
		}
	}

	private static String readHead(InputStream in) throws IOException {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		int last4 = 0, c;
		while ((c = in.read()) >= 0) {
			b.write(c);
			last4 = (last4 << 8) | c;
			if (last4 == 0x0D0A0D0A) return b.toString(StandardCharsets.ISO_8859_1);
			if (b.size() > 16_384) return null;
		}
		return null;
	}

	private static void respond(OutputStream out, int code, String type, byte[] body, boolean headOnly) throws IOException {
		String reason = switch (code) {
			case 200 -> "OK";
			case 404 -> "Not Found";
			case 405 -> "Method Not Allowed";
			case 502 -> "Bad Gateway";
			default -> "Error";
		};
		String h = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
				+ "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
		out.write(h.getBytes(StandardCharsets.ISO_8859_1));
		if (!headOnly) out.write(body);
		out.flush();
	}

	private synchronized byte[] playerHtml() throws IOException {
		if (playerHtml == null) {
			try (InputStream in = LiveStreamService.class.getResourceAsStream("/assets/mediarift/live_player.html")) {
				if (in == null) throw new IOException("live_player.html is missing in the mod");
				playerHtml = in.readAllBytes();
			}
		}
		return playerHtml;
	}

	private String statusJson() {
		Session s = session;
		Pipeline p = pipeline;
		String state = s == null ? "idle" : s.state;
		String msg = s == null ? "" : s.message;
		double speed = (p == null || p.killed) ? 0 : p.speed;
		boolean vod = s != null && s.vod;
		return "{\"state\":\"" + esc(state) + "\",\"message\":\"" + esc(msg) + "\",\"speed\":"
				+ String.format(Locale.ROOT, "%.3f", speed) + ",\"vod\":" + vod + "}";
	}

	private static String esc(String s) {
		StringBuilder sb = new StringBuilder();
		for (char c : s.toCharArray()) {
			if (c == '"' || c == '\\') sb.append('\\').append(c);
			else if (c == '\n') sb.append("\\n");
			else if (c < 0x20) sb.append(' ');
			else sb.append(c);
		}
		return sb.toString();
	}

	/** The video request: starts streamlink + ffmpeg, then copies what ffmpeg makes to the browser until one side stops. */
	private void streamVideo(Socket sock, OutputStream out) throws IOException {
		final Session sess;
		final Pipeline p;
		synchronized (this) {
			sess = session;
			if (sess == null) {
				respond(out, 404, "text/plain; charset=utf-8", "No stream prepared".getBytes(StandardCharsets.UTF_8), false);
				return;
			}
			if (pipeline != null) pipeline.kill();   // a new request replaces the old one (reconnect, reload)
			p = new Pipeline(sess);
			pipeline = p;
		}
		sess.state = "starting";
		sess.message = "";
		try {
			p.start();
		} catch (IOException e) {
			p.kill();
			sess.state = "error";
			sess.message = "Could not start the converter: " + e.getMessage();
			respond(out, 502, "text/plain; charset=utf-8", sess.message.getBytes(StandardCharsets.UTF_8), false);
			return;
		}

		sock.setSoTimeout(0);
		ScheduledFuture<?> startDog = scheduler.schedule(() -> {
			if (!p.gotData && !p.killed) {
				p.timedOut = true;
				p.kill();
			}
		}, START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		ScheduledFuture<?> stallDog = scheduler.scheduleAtFixedRate(() -> {
			long w = p.writeStartNs;
			// a video with an end can wait as long as you like (the pipe is simply full): only a live stream is dropped
			if (!sess.vod && w != 0 && System.nanoTime() - w > WRITE_STALL_SECONDS * 1_000_000_000L) {
				sess.state = "idle";   // nobody is watching (paused for a long time): the page reconnects when you press play
				p.kill();
				try {
					sock.close();
				} catch (IOException ignored) {
				}
			}
		}, 10, 10, TimeUnit.SECONDS);

		boolean clientGone = false;
		try {
			InputStream in = p.ffmpeg.getInputStream();
			byte[] buf = new byte[64 * 1024];
			int n = in.read(buf);
			if (n <= 0) {
				// ffmpeg gave nothing at all
				startDog.cancel(false);
				String why = p.explainFailure();
				sess.state = "error";
				sess.message = why;
				log.accept("stream failed: " + why);
				respond(out, 502, "text/plain; charset=utf-8", why.getBytes(StandardCharsets.UTF_8), false);
				return;
			}
			p.gotData = true;
			startDog.cancel(false);
			out.write(("HTTP/1.1 200 OK\r\nContent-Type: video/webm\r\nCache-Control: no-store\r\n"
					+ "Accept-Ranges: none\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
			sess.state = "live";
			log.accept("stream is live: " + sess.pageUrl);
			while (n > 0) {
				p.writeStartNs = System.nanoTime();
				try {
					out.write(buf, 0, n);
					out.flush();
				} catch (IOException e) {
					clientGone = true;
					break;
				} finally {
					p.writeStartNs = 0;
				}
				n = in.read(buf);
			}
			if (!clientGone && !p.killed) {
				sess.state = "ended";
				sess.message = p.explainEnd();
				log.accept("stream ended: " + sess.message);
			}
		} finally {
			startDog.cancel(false);
			stallDog.cancel(false);
			p.kill();
			synchronized (this) {
				if (pipeline == p) pipeline = null;
			}
			if (clientGone && "live".equals(sess.state)) sess.state = "idle";
		}
	}

	// --------------------------------------------------------------- pipeline

	private static final Pattern SPEED = Pattern.compile("speed=\\s*([0-9.]+)x");

	private final class Pipeline {
		final Session sess;
		Process streamlink, ffmpeg;
		volatile boolean gotData, killed, timedOut;
		volatile long writeStartNs;
		volatile double speed;
		private final Deque<String> slLines = new ArrayDeque<>(), ffLines = new ArrayDeque<>();

		Pipeline(Session s) {
			this.sess = s;
		}

		void start() throws IOException {
			List<String> ffCmd = ffmpegCommand(sess);
			List<String> slCmd = streamlinkCommand(sess);
			log.accept("streamlink: " + String.join(" ", slCmd));
			log.accept("ffmpeg: " + String.join(" ", ffCmd));

			ProcessBuilder slPb = new ProcessBuilder(slCmd);
			slPb.environment().put("PYTHONUNBUFFERED", "1");
			slPb.environment().put("PYTHONIOENCODING", "utf-8");
			streamlink = slPb.start();
			try {
				ffmpeg = new ProcessBuilder(ffCmd).start();
			} catch (IOException e) {
				killProcess(streamlink);
				throw e;
			}
			try {
				streamlink.getOutputStream().close();
			} catch (IOException ignored) {
			}

			drain(streamlink, slLines, false);
			drain(ffmpeg, ffLines, true);

			final Process sl = streamlink, ff = ffmpeg;
			Thread pump = new Thread(() -> {
				try (InputStream from = sl.getInputStream(); OutputStream to = ff.getOutputStream()) {
					byte[] b = new byte[64 * 1024];
					int n;
					while ((n = from.read(b)) > 0) {
						to.write(b, 0, n);
						to.flush();
					}
				} catch (IOException ignored) {
					// ffmpeg or streamlink stopped
				} finally {
					// streamlink is over -> ffmpeg sees the end of its input, finishes and closes; if ffmpeg is over -> stop streamlink
					if (!ff.isAlive()) killProcess(sl);
				}
			}, "MediaRift-LivePump");
			pump.setDaemon(true);
			pump.start();
		}

		private void drain(Process p, Deque<String> keep, boolean isFfmpeg) {
			Thread t = new Thread(() -> {
				try (InputStream in = p.getErrorStream()) {
					StringBuilder line = new StringBuilder();
					int c;
					while ((c = in.read()) >= 0) {
						if (c == '\r' || c == '\n') {
							if (line.length() > 0) handleLine(line.toString(), keep, isFfmpeg);
							line.setLength(0);
						} else if (line.length() < 2000) {
							line.append((char) c);
						}
					}
					if (line.length() > 0) handleLine(line.toString(), keep, isFfmpeg);
				} catch (IOException ignored) {
				}
			}, "MediaRift-LiveLog");
			t.setDaemon(true);
			t.start();
		}

		private void handleLine(String l, Deque<String> keep, boolean isFfmpeg) {
			if (isFfmpeg && l.contains("speed=")) {
				Matcher m = SPEED.matcher(l);
				if (m.find()) {
					try {
						speed = Double.parseDouble(m.group(1));
					} catch (NumberFormatException ignored) {
					}
				}
				return;   // a progress line, not worth keeping
			}
			synchronized (keep) {
				keep.addLast(l.strip());
				while (keep.size() > 12) keep.removeFirst();
			}
		}

		private String lastLines(Deque<String> d) {
			synchronized (d) {
				return String.join(" | ", d);
			}
		}

		private String lastLine(Deque<String> d, String contains) {
			synchronized (d) {
				String hit = null;
				for (String s : d) if (s.toLowerCase(Locale.ROOT).contains(contains)) hit = s;
				return hit;
			}
		}

		/** Nothing came out at all: why? (a message for the player) */
		String explainFailure() {
			if (timedOut) return "The stream did not start within " + START_TIMEOUT_SECONDS + " seconds. Try again, or check the address.";
			try {
				Thread.sleep(300);   // let the log readers catch up
			} catch (InterruptedException ignored) {
			}
			return explain(true);
		}

		/** The video stopped after it had been running. */
		String explainEnd() {
			return explain(false);
		}

		private String explain(boolean startup) {
			String sl = lastLines(slLines);
			String low = sl.toLowerCase(Locale.ROOT);
			if (low.contains("no playable streams")) {
				return "Nothing to play here: the channel is offline, the stream has not started yet, or this site is not supported by streamlink.";
			}
			if (low.contains("unable to open url") || low.contains("no plugin can handle")) {
				return "Streamlink cannot open this address. It only works with live streams (Twitch, YouTube live, Kick ...). " + tail(sl);
			}
			if (low.contains("could not be found") && low.contains("stream")) {
				return "Streamlink did not find a matching quality. The streamer may not offer this quality: try another Resolution in the mod settings. " + tail(sl);
			}
			String ffErr = lastLine(ffLines, "error");
			if (ffErr == null && !sl.isBlank()) return (startup ? "Streamlink: " : "The stream stopped. Streamlink: ") + tail(sl);
			if (ffErr != null) return "ffmpeg: " + tail(ffErr);
			return startup ? "The converter stopped without a picture." : "The stream has ended.";
		}

		private String tail(String s) {
			return s.length() > 260 ? s.substring(s.length() - 260) : s;
		}

		void kill() {
			killed = true;
			killProcess(streamlink);
			killProcess(ffmpeg);
		}
	}

	private static void killProcess(Process p) {
		ProcUtil.destroyTree(p);
	}

	// ------------------------------------------------------------ the commands

	private static List<String> streamlinkCommand(Session s) {
		List<String> c = new ArrayList<>();
		c.add(s.streamlink.toString());
		c.add("--stdout");
		c.add("--loglevel");
		c.add("warning");
		c.add("--hls-live-edge");
		c.add("2");
		if (s.opts.lowLatency) c.add("--twitch-low-latency");
		c.add(s.pageUrl);
		c.add(s.opts.quality == null || s.opts.quality.isBlank() ? "best" : s.opts.quality.strip());
		return c;
	}

	static List<String> ffmpegCommand(Session s) {
		StreamOptions o = s.opts;
		boolean vp9 = !"vp8".equalsIgnoreCase(o.codec);
		// 1080p needs more of the processor than 720p
		int maxAuto = o.maxHeight > 720 ? 8 : 6;
		int threads = o.threads > 0 ? o.threads : Math.max(2, Math.min(maxAuto, Runtime.getRuntime().availableProcessors() / 2));

		List<String> c = new ArrayList<>(List.of(s.ffmpeg.toString(), "-hide_banner", "-loglevel", "warning", "-stats"));
		// a video with an end: read it at normal speed, so the converter does not run far ahead of what you watch
		if (s.vod) c.add("-re");
		// read the stream with as little buffering as possible
		c.addAll(List.of("-fflags", "+nobuffer+discardcorrupt", "-flags", "low_delay",
				"-probesize", "1000000", "-analyzeduration", "1000000",
				"-i", "pipe:0"));
		c.addAll(List.of("-map", "0:v:0", "-map", "0:a:0?"));

		// never bigger than maxHeight / maxFps; "-2" keeps the width even
		// (below 60 the frame rate is limited; 60 or more = the stream keeps its own 30 / 60, nothing is repeated)
		String vf = (o.maxFps > 0 && o.maxFps < 60 ? "fps=" + o.maxFps + "," : "") + "scale=-2:min(" + o.maxHeight + "\\,ih)";
		c.addAll(List.of("-vf", vf, "-pix_fmt", "yuv420p"));

		String kb = o.videoKbps + "k";
		if (vp9) {
			c.addAll(List.of("-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-row-mt", "1", "-tile-columns", "2"));
		} else {
			c.addAll(List.of("-c:v", "libvpx", "-deadline", "realtime", "-cpu-used", "8", "-auto-alt-ref", "0"));
		}
		c.addAll(List.of("-threads", String.valueOf(threads), "-lag-in-frames", "0", "-error-resilient", "1", "-g", "90",
				"-b:v", kb, "-maxrate", (int) (o.videoKbps * 1.2) + "k", "-bufsize", kb));
		c.addAll(List.of("-c:a", "libopus", "-b:a", o.audioKbps + "k", "-ar", "48000", "-ac", "2", "-application", "lowdelay"));
		c.addAll(List.of("-max_muxing_queue_size", "1024", "-f", "webm", "-live", "1", "-flush_packets", "1", "pipe:1"));
		return c;
	}
}
