package com.mef1sh.mediarift;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One-time move of the files of the old name ("Music Browser") to the new names, so a player who had the old version
 * keeps the settings, the start page and the downloaded streamlink / ffmpeg. Does nothing for everybody else.
 */
final class LegacyMigration {
	private LegacyMigration() {
	}

	static void run() {
		Path config = FabricLoader.getInstance().getConfigDir();
		Path game = FabricLoader.getInstance().getGameDir();
		try {
			Path oldConfig = config.resolve("music_browser.json");
			Path newConfig = config.resolve("mediarift.json");
			if (Files.isRegularFile(oldConfig) && !Files.exists(newConfig)) {
				// the names of the hotkeys inside the file contain the old mod id
				String text = Files.readString(oldConfig, StandardCharsets.UTF_8).replace("spotify_browser", "mediarift");
				Files.writeString(newConfig, text, StandardCharsets.UTF_8);
			}
			move(config.resolve("music_browser_home.txt"), config.resolve("mediarift_home.txt"));
			move(game.resolve("music_browser_tools"), game.resolve("mediarift_tools"));
		} catch (IOException | RuntimeException ignored) {
			// not important: the mod simply starts with its defaults
		}
	}

	private static void move(Path from, Path to) throws IOException {
		if (Files.exists(from) && !Files.exists(to)) Files.move(from, to);
	}
}
