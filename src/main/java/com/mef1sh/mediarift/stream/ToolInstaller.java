package com.mef1sh.mediarift.stream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Downloads streamlink and ffmpeg into the game folder (Windows only).
 * Used by the loading screen at game start and by /sb live install.
 */
public final class ToolInstaller {
	private ToolInstaller() {
	}

	/** Who wants to hear about the progress (the loading screen, or the chat). Called from the download thread. */
	public interface Listener {
		/** A line of text, for example "ffmpeg: unpacking...". */
		void status(String text);

		/** Download progress of one file. total = -1 when the size is not known. */
		default void progress(String label, long done, long total) {
		}
	}

	private static final List<String> STREAMLINK_URLS = List.of("https://github.com/mef1sh/music-browser-tools/raw/main/streamlink.zip");
	private static final List<String> FFMPEG_URLS = List.of(
			"https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip",
			"https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip");

	private static final String MANUAL_HELP =
			"You can also install them yourself: winget install Gyan.FFmpeg and winget install Streamlink.Streamlink "
					+ "(then restart the game), or set ffmpegPath / streamlinkPath in config/mediarift.json.";

	/** The automatic download works on Windows only (other systems: install with the package manager). */
	public static boolean canAutoInstall() {
		return ToolLocator.WINDOWS;
	}

	/** @return true if something is missing (so the loading screen is needed). */
	public static boolean needsDownload(Path toolsDir, StreamOptions o) {
		return ToolLocator.find("ffmpeg", o.ffmpegPath, toolsDir) == null
				|| ToolLocator.find("streamlink", o.streamlinkPath, toolsDir) == null;
	}

	/** Downloads what is missing. Blocks until it is done. */
	public static void install(Path toolsDir, StreamOptions o, Listener say) throws IOException {
		if (!ToolLocator.WINDOWS) {
			throw new IOException("Automatic download only works on Windows. Install ffmpeg and streamlink with your package manager "
					+ "(for example: sudo apt install ffmpeg streamlink, or brew install ffmpeg streamlink).");
		}
		Files.createDirectories(toolsDir);
		boolean needFfmpeg = ToolLocator.find("ffmpeg", o.ffmpegPath, toolsDir) == null;
		boolean needStreamlink = ToolLocator.find("streamlink", o.streamlinkPath, toolsDir) == null;
		if (!needFfmpeg && !needStreamlink) {
			say.status("ffmpeg and streamlink are already there.");
			return;
		}
		try {
			if (needStreamlink) installStreamlink(toolsDir, say);
			if (needFfmpeg) installFfmpeg(toolsDir, say);
		} catch (IOException e) {
			throw new IOException(e.getMessage() + "  " + MANUAL_HELP, e);
		}
	}

	private static void installStreamlink(Path toolsDir, Listener say) throws IOException {
		Path zip = Files.createTempFile("streamlink", ".zip");
		try {
			downloadFirstWorking(STREAMLINK_URLS, zip, "Streamlink", say);
			Path target = toolsDir.resolve("streamlink");
			deleteTree(target);
			say.status("Streamlink: unpacking...");
			unzip(zip, target, null);
		} finally {
			Files.deleteIfExists(zip);
		}
		Path exe = ToolLocator.find("streamlink", "", toolsDir);
		if (exe == null) throw new IOException("The streamlink download has no streamlink.exe inside.");
		String v = ToolLocator.version(exe, "--version");
		if (v == null) throw new IOException("Streamlink was unpacked but does not start.");
		say.status("Streamlink is ready: " + v);
	}

	private static void installFfmpeg(Path toolsDir, Listener say) throws IOException {
		Path zip = Files.createTempFile("ffmpeg", ".zip");
		try {
			downloadFirstWorking(FFMPEG_URLS, zip, "ffmpeg", say);
			Path target = toolsDir.resolve("ffmpeg");
			deleteTree(target);
			say.status("ffmpeg: unpacking...");
			// only ffmpeg.exe is needed, not the other programs / documentation in the archive
			unzip(zip, target, name -> name.replace('\\', '/').toLowerCase().endsWith("/bin/ffmpeg.exe")
					|| name.equalsIgnoreCase("ffmpeg.exe"));
		} finally {
			Files.deleteIfExists(zip);
		}
		Path exe = ToolLocator.find("ffmpeg", "", toolsDir);
		if (exe == null) throw new IOException("The ffmpeg download has no ffmpeg.exe inside.");
		String v = ToolLocator.version(exe, "-version");
		if (v == null) throw new IOException("ffmpeg was unpacked but does not start.");
		if (!ToolLocator.hasEncoders(exe)) throw new IOException("This ffmpeg build has no VP9 / Opus encoder.");
		say.status("ffmpeg is ready.");
	}

	/** Tries the addresses one after another, the first one that downloads wins. */
	private static void downloadFirstWorking(List<String> urls, Path to, String label, Listener say) throws IOException {
		IOException last = null;
		for (String url : urls) {
			try {
				say.status(label + ": downloading...");
				download(url, to, label, say);
				return;
			} catch (IOException e) {
				last = e;
				say.status(label + ": " + url.substring(0, Math.min(url.length(), 60)) + " failed (" + e.getMessage() + ")");
			}
		}
		throw new IOException(label + " could not be downloaded" + (last == null ? "." : ": " + last.getMessage()), last);
	}

	private static void download(String url, Path to, String label, Listener say) throws IOException {
		URLConnection c = new URL(url).openConnection();
		c.setRequestProperty("User-Agent", "MediaRift");
		c.setConnectTimeout(15000);
		c.setReadTimeout(30000);
		long total = c.getContentLengthLong();
		long done = 0, lastReport = 0;
		try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(to)) {
			byte[] buf = new byte[64 * 1024];
			int n;
			while ((n = in.read(buf)) > 0) {
				out.write(buf, 0, n);
				done += n;
				if (done - lastReport > 256 * 1024) {
					lastReport = done;
					say.progress(label, done, total);
				}
			}
		}
		say.progress(label, done, total);
	}

	/** Unpacks a zip. filter = null: everything (keeps the folders); otherwise only matching files, all put flat into the target. */
	private static void unzip(Path zip, Path target, Predicate<String> filter) throws IOException {
		Files.createDirectories(target);
		Path root = target.toAbsolutePath().normalize();
		try (ZipFile zf = new ZipFile(zip.toFile())) {
			Enumeration<? extends ZipEntry> en = zf.entries();
			while (en.hasMoreElements()) {
				ZipEntry e = en.nextElement();
				if (e.isDirectory()) continue;
				if (filter != null && !filter.test(e.getName())) continue;
				Path out;
				if (filter == null) {
					out = root.resolve(e.getName()).normalize();
				} else {
					String n = e.getName().replace('\\', '/');
					out = root.resolve(n.substring(n.lastIndexOf('/') + 1)).normalize();
				}
				if (!out.startsWith(root)) throw new IOException("Bad file name in the archive: " + e.getName());   // zip-slip protection
				Files.createDirectories(out.getParent());
				try (InputStream in = zf.getInputStream(e)) {
					Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
				}
				out.toFile().setExecutable(true, false);   // zip files carry no "runnable" flag (matters on Linux / macOS)
			}
		}
	}

	private static void deleteTree(Path p) throws IOException {
		if (!Files.exists(p)) return;
		try (var s = Files.walk(p)) {
			for (Path x : (Iterable<Path>) s.sorted(java.util.Comparator.reverseOrder())::iterator) Files.deleteIfExists(x);
		}
	}
}
