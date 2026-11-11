package com.mef1sh.mediarift.stream;

/** Settings of the live stream converter. Filled from PlayerConfig (config/mediarift.json). */
public final class StreamOptions {
	/** Full path of ffmpeg / streamlink, or empty = look for it automatically. */
	public String ffmpegPath = "";
	public String streamlinkPath = "";
	/** What streamlink should pick, the first one that exists wins (we scale down afterwards anyway). */
	public String quality = "best";
	/** The picture is never bigger than this (pixels of height). */
	public int maxHeight = 1080;
	/** The picture never has more frames per second than this. 0 = keep what the stream has. */
	public int maxFps = 60;
	public int videoKbps = 8000;
	public int audioKbps = 128;
	/** "vp9" (better picture) or "vp8" (lighter for a weak CPU). */
	public String codec = "vp9";
	/** Encoder threads. 0 = automatic. */
	public int threads = 0;
	/** Ask streamlink for the lowest delay it can give. */
	public boolean lowLatency = true;
}
