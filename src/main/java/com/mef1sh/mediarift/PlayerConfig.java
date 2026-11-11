package com.mef1sh.mediarift;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** All player settings. Saved to  config/mediarift.json  (you can also change them in Mod Menu). */
public class PlayerConfig {
	/** Size of the whole player in percent (50..200). */
	public int sizePercent = 75;
	/** Width and height of the window in percent (50..200), on top of the overall size. */
	public float widthPercent = 110;
	public float heightPercent = 100;
	/** Page zoom inside the browser in tenths: -15 = zoom level -1.5 (smaller page, more fits in the window). */
	public int zoomTenths = -25;
	/** Extra keys that must be held for a hotkey: id of the hotkey -> 1 = Shift, 2 = Ctrl, 4 = Alt (added together). */
	public java.util.Map<String, Integer> keyMods = new java.util.HashMap<>();
	/** Horizontal place: 0 = left edge, 100 = right edge. */
	public float posX = 0;   // new installs: the window starts in the top-LEFT corner
	/** Vertical place: 0 = top edge, 100 = bottom edge. */
	public int posY = 0;

	/** false = the whole bottom row (back / pause / forward) is gone. */
	public boolean showButtonRow = true;
	public boolean showPrev = true;
	public boolean showPlay = true;
	public boolean showNext = true;
	/** true = the 3 buttons disappear when the chat is closed (pinned mode). */
	public boolean hideButtonsWhenChatClosed = true;

	/** true = the player stays on EVERY screen (title screen, settings...) and the music is not stopped when you leave the world. */
	public boolean superPinned = false;
	/** The gray bar with the scrolling song name. false = it is gone (in the chat two small buttons stay instead). */
	public boolean showTicker = true;
	/** true = the bar disappears when the chat is closed (pinned mode). */
	public boolean hideTickerWhenChatClosed = false;
	/** The vertical volume slider next to the player. */
	public boolean showVolumeBar = true;
	/** true = the volume slider disappears when the chat is closed (pinned mode). */
	public boolean hideVolumeBarWhenChatClosed = true;

	/** true = the player stays on screen when the chat is closed. */
	public boolean pinned = false;
	/** Volume of the music in percent (0..100). */
	public int volume = 100;

	// ---- live streams: the browser cannot play H.264 / AAC, so streamlink + ffmpeg convert it to VP9 / Opus (only for streams)
	/** false = the whole live stream feature is off. */
	public boolean liveEnabled = true;
	/** true = a live stream (YouTube live, a Twitch or Kick channel) is converted automatically when you open it. false = nothing is converted by itself. */
	public boolean liveAuto = true;
	/** true = at game start, a loading screen downloads streamlink and ffmpeg if they are missing (Windows). */
	public boolean liveAutoDownload = true;
	/**
	 * RESOLUTION (settings, "Resolution"): 720 (recommended), 1080 (the maximum, heavy for the processor) or 480 (for weak computers). The picture of streams (Twitch, Kick, YouTube live) is never taller
	 * than this, and YouTube videos are switched to it. A stream gives less only if the streamer does not send more.
	 */
	public int resolution = 720;
	/** false = the player window is drawn IN FRONT of the buttons of the pause menu / title screen, true = BEHIND them. */
	public boolean behindMenus = false;
	/** How visible the player WINDOW (the site / video) is, in percent (10..100). 100 = solid, less = see-through. Settings only. */
	public int windowOpacity = 100;
	/** How visible the gray bar with the song name, the pin and the round buttons and the volume slider are, in percent (10..100). Settings only. */
	public int uiOpacity = 100;
	/** The converted picture never has more frames per second than this. 60 or more = no limit (the stream keeps its own 30 / 60). */
	public int liveMaxFps = 60;
	/** Bitrate of the converted picture in kbit/s. 0 = automatic (by the resolution). */
	public int liveVideoKbps = 0;
	public int liveAudioKbps = 128;
	/** "vp9" (better picture) or "vp8" (easier for a weak CPU). */
	public String liveCodec = "vp9";
	/** Threads for the converter. 0 = automatic. */
	public int liveThreads = 0;
	/** Own ffmpeg / streamlink (a file, or a folder). Empty = the mod finds or downloads them itself. */
	public String ffmpegPath = "";
	public String streamlinkPath = "";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mediarift.json");
	private static PlayerConfig instance;

	public static PlayerConfig get() {
		if (instance == null) instance = load();
		return instance;
	}

	private static PlayerConfig load() {
		PlayerConfig c = null;
		try {
			if (Files.exists(FILE)) {
				c = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), PlayerConfig.class);
			}
		} catch (Exception ignored) {
		}
		if (c == null) c = new PlayerConfig();
		c.clamp();
		return c;
	}

	private static int clampInt(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	private static float clampF(float v, float min, float max) {
		if (Float.isNaN(v)) return min;
		return Math.max(min, Math.min(max, v));
	}

	public int modsOf(String hotkeyId) {
		if (keyMods == null) return 0;
		Integer m = keyMods.get(hotkeyId);
		return m == null ? 0 : (m & 7);
	}

	public void setModsOf(String hotkeyId, int mods) {
		if (keyMods == null) keyMods = new java.util.HashMap<>();
		if ((mods & 7) == 0) keyMods.remove(hotkeyId);
		else keyMods.put(hotkeyId, mods & 7);
	}

	/** Which preset the size hotkey set last (-1 = none yet since the game started). Not saved. */
	private transient int presetStep = -1;

	/**
	 * The size hotkey: small -> normal -> big -> small, the SAME three presets as the "Small / Normal / Big" buttons in the
	 * settings (overall size, width, height and page zoom together). The first press after the game starts, and the first press
	 * after you changed the settings by hand, gives the small one.
	 *
	 * @return 0 = small, 1 = normal, 2 = big
	 */
	public int cycleSize() {
		int next = 0;
		if (presetStep >= 0 && isPreset(presetStep)) next = (presetStep + 1) % 3;
		applyPreset(next);
		presetStep = next;
		clamp();
		save();
		return next;
	}

	/** Are the settings exactly the preset n now? */
	private boolean isPreset(int n) {
		PlayerConfig p = new PlayerConfig();
		p.applyPreset(n);
		return sizePercent == p.sizePercent && widthPercent == p.widthPercent
				&& heightPercent == p.heightPercent && zoomTenths == p.zoomTenths;
	}

	public void clamp() {
		sizePercent = clampInt(sizePercent, 50, 200);
		widthPercent = clampF(widthPercent, 20f, 400f);   // the resize button can go beyond the sliders (50..200)
		heightPercent = clampF(heightPercent, 20f, 400f);
		zoomTenths = clampInt(zoomTenths, -40, 10);
		posX = clampF(posX, 0f, 100f);
		posY = clampInt(posY, 0, 100);
		volume = clampInt(volume, 0, 100);
		resolution = resolution >= 1000 ? 1080 : (resolution >= 600 ? 720 : 480);   // only 1080 / 720 / 480
		windowOpacity = clampInt(windowOpacity, 10, 100);
		uiOpacity = clampInt(uiOpacity, 10, 100);
		liveMaxFps = liveMaxFps <= 0 ? 0 : clampInt(liveMaxFps, 10, 60);
		liveVideoKbps = liveVideoKbps <= 0 ? 0 : clampInt(liveVideoKbps, 300, 20000);
		liveAudioKbps = clampInt(liveAudioKbps, 32, 320);
		liveThreads = clampInt(liveThreads, 0, 16);
		if (liveCodec == null || !liveCodec.trim().equalsIgnoreCase("vp8")) liveCodec = "vp9";
		else liveCodec = "vp8";
		if (ffmpegPath == null) ffmpegPath = "";
		if (streamlinkPath == null) streamlinkPath = "";
	}

	public void save() {
		clamp();
		try {
			Files.writeString(FILE, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException ignored) {
		}
	}

	/** The pin button has 3 states: 0 = off (white), 1 = pinned (green), 2 = super pin (purple). */
	public int pinMode() {
		return superPinned ? 2 : (pinned ? 1 : 0);
	}

	public void setPinMode(int mode) {
		pinned = mode == 1;
		superPinned = mode == 2;
	}

	/** Recommended sets: 0 = small, 1 = normal, 2 = big. */
	public void applyPreset(int n) {
		if (n == 0) {          // small: a 75 % player, the page zoomed out more so the site still fits
			sizePercent = 75; widthPercent = 110; heightPercent = 100; zoomTenths = -25;
		} else if (n == 1) {   // normal
			sizePercent = 100; widthPercent = 110; heightPercent = 100; zoomTenths = -15;
		} else {               // big
			sizePercent = 130; widthPercent = 115; heightPercent = 105; zoomTenths = -10;
		}
	}

	/** Which of the 3 buttons are on: 0 = back, 1 = play/pause, 2 = forward. */
	public int[] visibleActions() {
		if (!showButtonRow) return new int[0];
		int n = (showPrev ? 1 : 0) + (showPlay ? 1 : 0) + (showNext ? 1 : 0);
		int[] r = new int[n];
		int i = 0;
		if (showPrev) r[i++] = 0;
		if (showPlay) r[i++] = 1;
		if (showNext) r[i++] = 2;
		return r;
	}
}
