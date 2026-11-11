package com.mef1sh.mediarift;

import com.mef1sh.mediarift.stream.StreamOptions;
import com.mef1sh.mediarift.stream.ToolInstaller;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** Glue between the game (settings, chat, folders) and the live stream code in the "stream" package. */
final class LiveTools {
	private LiveTools() {
	}

	/** streamlink and ffmpeg are downloaded to  <game folder>/mediarift_tools/  */
	static Path toolsDir() {
		return FabricLoader.getInstance().getGameDir().resolve("mediarift_tools");
	}

	/** The settings from config/mediarift.json as the converter wants them. */
	static StreamOptions options() {
		PlayerConfig c = PlayerConfig.get();
		StreamOptions o = new StreamOptions();
		o.ffmpegPath = c.ffmpegPath;
		o.streamlinkPath = c.streamlinkPath;
		int res = streamResolution(c);
		o.quality = qualityFor(res);
		o.maxHeight = res;
		o.maxFps = c.liveMaxFps;
		o.videoKbps = c.liveVideoKbps > 0 ? c.liveVideoKbps : autoKbps(res);
		o.audioKbps = c.liveAudioKbps;
		o.codec = c.liveCodec;
		o.threads = c.liveThreads;
		return o;
	}

	/**
	 * The resolution that streams really get. With the VP8 codec (for very weak computers) streams are limited to 720p, even if the
	 * setting says 1080 (the setting itself stays, so with VP9 the 1080 is back). YouTube videos are not affected (they do not use the converter).
	 */
	static int streamResolution(PlayerConfig c) {
		return "vp8".equalsIgnoreCase(c.liveCodec) ? Math.min(c.resolution, 720) : c.resolution;
	}

	/** Writes a line into the chat (can be called from any thread). */
	static void chat(String message) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.gui != null) addToChat(mc.gui.hud.getChat(), Component.literal(message));
		});
	}

	/**
	 * 26.1 removed the one-argument ChatComponent.addMessage(Component). The new name for "a client message" is looked up
	 * by name, so the mod compiles whichever of them the game has (first one that exists wins).
	 */
	private static void addToChat(ChatComponent chat, Component text) {
		for (String name : new String[] {"addClientSystemMessage", "addMessage"}) {
			try {
				chat.getClass().getMethod(name, Component.class).invoke(chat, text);
				return;
			} catch (ReflectiveOperationException ignored) {
				// try the next name
			}
		}
		MediaRiftClient.LOGGER.warn("[live] could not write to chat: {}", text.getString());
	}

	/**
	 * What streamlink picks for a resolution (the first that exists wins). 1080 = "best": the most the streamer gives (the picture
	 * is scaled down to 1080 if it is bigger). 720 / 480: the best one up to that size, and "best" as the last way out.
	 */
	static String qualityFor(int resolution) {
		if (resolution >= 1000) return "best";
		if (resolution >= 600) return "720p60,720p,480p,360p,best";
		return "480p,360p,best";
	}

	/** Bitrate (kbit/s) that fits the resolution. */
	static int autoKbps(int resolution) {
		if (resolution >= 1000) return 8000;
		if (resolution >= 600) return 4500;
		return 2000;
	}

	private static final AtomicBoolean INSTALLING = new AtomicBoolean(false);

	/**
	 * Downloads streamlink + ffmpeg in the background (progress in the chat). Used by /sb install and by itself
	 * when a stream is opened and the programs are missing.
	 *
	 * @return a message for the chat
	 */
	static String installInBackground() {
		if (!ToolInstaller.canAutoInstall()) {
			return "The automatic download only works on Windows. Install ffmpeg and streamlink with your package manager.";
		}
		if (!INSTALLING.compareAndSet(false, true)) return "The download is already running (progress in the chat).";
		Thread t = new Thread(() -> {
			try {
				ToolInstaller.install(toolsDir(), options(), LiveTools::chat);
				chat("Done. Streams and videos are ready: open them again.");
			} catch (Throwable e) {
				chat("Download failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
			} finally {
				INSTALLING.set(false);
			}
		}, "MediaRift-ToolsDownload");
		t.setDaemon(true);
		t.start();
		return "Downloading streamlink and ffmpeg (progress in the chat)...";
	}

	/** If a program is missing (the first download did not finish), starts the download. @return text to add to an error message, or "" */
	static String autoInstallIfMissing() {
		try {
			if (ToolInstaller.canAutoInstall() && ToolInstaller.needsDownload(toolsDir(), options())) {
				return "  " + installInBackground();
			}
		} catch (Throwable ignored) {
		}
		return "";
	}
}
