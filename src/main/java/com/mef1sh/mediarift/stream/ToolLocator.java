package com.mef1sh.mediarift.stream;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** Finds ffmpeg and streamlink: setting -> our own tools folder -> PATH -> the usual install places. */
public final class ToolLocator {
	private ToolLocator() {
	}

	static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

	private static final Map<String, Boolean> ENCODER_CACHE = new ConcurrentHashMap<>();

	/** @param tool "ffmpeg" or "streamlink". @return the program, or null if it was not found. */
	public static Path find(String tool, String configured, Path toolsDir) {
		String exe = WINDOWS ? tool + ".exe" : tool;

		// 1. a path from the settings (a file, or a folder that contains it)
		if (configured != null && !configured.isBlank()) {
			try {
				Path p = Paths.get(configured.trim());
				if (Files.isRegularFile(p)) return p;
				if (Files.isDirectory(p)) {
					for (Path c : new Path[]{p.resolve(exe), p.resolve("bin").resolve(exe)}) {
						if (Files.isRegularFile(c)) return c;
					}
				}
			} catch (Exception ignored) {
			}
		}

		// 2. the folder where the mod downloads the programs: <tools>/ffmpeg and <tools>/streamlink.
		//    Each one is searched ONLY in its own folder (the streamlink package brings a small ffmpeg of its own that cannot write VP9).
		Path own = toolsDir == null ? null : toolsDir.resolve(tool);
		if (own != null && Files.isDirectory(own)) {
			try (Stream<Path> s = Files.walk(own, 6)) {
				Path hit = s.filter(x -> x.getFileName() != null && x.getFileName().toString().equalsIgnoreCase(exe)
						&& Files.isRegularFile(x)).findFirst().orElse(null);
				if (hit != null) return hit;
			} catch (IOException ignored) {
			}
		}

		// 3. PATH
		String path = System.getenv("PATH");
		if (path != null) {
			for (String dir : path.split(File.pathSeparator)) {
				if (dir.isBlank()) continue;
				try {
					Path c = Paths.get(dir.trim().replace("\"", "")).resolve(exe);
					if (Files.isRegularFile(c)) return c;
				} catch (Exception ignored) {
				}
			}
		}

		// 4. the places where installers / winget / scoop / chocolatey put them
		for (Path c : knownPlaces(tool, exe)) {
			try {
				if (Files.isRegularFile(c)) return c;
			} catch (Exception ignored) {
			}
		}
		return null;
	}

	private static List<Path> knownPlaces(String tool, String exe) {
		List<Path> l = new ArrayList<>();
		if (WINDOWS) {
			String pf = System.getenv("ProgramFiles"), local = System.getenv("LOCALAPPDATA"),
					home = System.getProperty("user.home"), pd = System.getenv("ProgramData");
			if (pf != null) {
				if (tool.equals("streamlink")) l.add(Paths.get(pf, "Streamlink", "bin", exe));
				else {
					l.add(Paths.get(pf, "ffmpeg", "bin", exe));
					l.add(Paths.get(pf, "FFmpeg", "bin", exe));
				}
			}
			if (local != null) {
				l.add(Paths.get(local, "Microsoft", "WinGet", "Links", exe));
				if (tool.equals("streamlink")) l.add(Paths.get(local, "Programs", "Streamlink", "bin", exe));
			}
			if (home != null) l.add(Paths.get(home, "scoop", "shims", exe));
			if (pd != null) l.add(Paths.get(pd, "chocolatey", "bin", exe));
			l.add(Paths.get("C:\\ffmpeg\\bin", exe));
		} else {
			for (String d : new String[]{"/usr/local/bin", "/usr/bin", "/opt/homebrew/bin", "/snap/bin"}) l.add(Paths.get(d, exe));
		}
		return l;
	}

	/** First line of "program --version", or null if it does not run. */
	public static String version(Path program, String versionArg) {
		String out = ProcUtil.capture(List.of(program.toString(), versionArg), 15);
		if (out == null) return null;
		String line = out.strip().split("\\R", 2)[0];
		return line.isBlank() ? null : line;
	}

	/** true if this ffmpeg can write VP9 and Opus (some tiny builds cannot). The answer is remembered. */
	public static boolean hasEncoders(Path ffmpeg) {
		return ENCODER_CACHE.computeIfAbsent(ffmpeg.toString(), k -> {
			String out = ProcUtil.capture(List.of(k, "-hide_banner", "-encoders"), 20);
			return out != null && out.contains("libvpx-vp9") && out.contains("libopus");
		});
	}
}
