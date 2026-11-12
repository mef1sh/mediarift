package com.mef1sh.mediarift;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import static org.lwjgl.sdl.SDLScancode.*;   // 26.3: the game uses SDL3 instead of GLFW, keys are SDL key numbers

import java.util.ArrayList;
import java.util.List;

/**
 * Hotkeys. They also show up in Minecraft's own Controls screen (category "MediaRift"),
 * and the settings screen of the mod has a tab to change them.
 * They work in the game when no screen is open, even if the on-screen buttons are hidden, and also in every menu
 * (pause menu, title screen, inventory, ...), pinned or not.
 */
public final class ModKeys {
	/** 1.21.9+: a category is an object now. Its name comes from the language file: key.category.mediarift.main */
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("mediarift", "main"));

	/** All keys, in the order they appear in the settings screen. */
	public static final List<KeyMapping> ALL = new ArrayList<>();
	/** Short names for the settings screen (translation keys), same order as ALL. */
	public static final List<String> SHORT_NAMES = new ArrayList<>();

	public static KeyMapping TOGGLE_WINDOW, PLAY_PAUSE, PREV, NEXT, VOL_UP, VOL_DOWN, PIN, RELOAD, BACK, FORWARD, OPEN_SETTINGS, SHORT_PREV, SHORT_NEXT, SIZE;

	private ModKeys() {
	}

	private static KeyMapping make(String id, int defaultKey, String shortName) {
		KeyMapping kb = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.mediarift." + id, defaultKey, CATEGORY));
		ALL.add(kb);
		SHORT_NAMES.add("mediarift.config.key." + shortName);
		return kb;
	}

	/** Default keys are on the numpad, so they do not clash with the normal game keys. Change them any time. */
	public static void register() {
		TOGGLE_WINDOW = make("toggle_window", SDL_SCANCODE_KP_0, "toggle");
		PLAY_PAUSE = make("play_pause", SDL_SCANCODE_KP_5, "play");
		PREV = make("prev", SDL_SCANCODE_KP_4, "prev");
		NEXT = make("next", SDL_SCANCODE_KP_6, "next");
		VOL_UP = make("vol_up", SDL_SCANCODE_KP_8, "volup");
		VOL_DOWN = make("vol_down", SDL_SCANCODE_KP_2, "voldown");
		PIN = make("pin", SDL_SCANCODE_KP_PERIOD, "pin");
		RELOAD = make("reload", SDL_SCANCODE_KP_MULTIPLY, "reload");
		BACK = make("back", SDL_SCANCODE_KP_MINUS, "back");
		FORWARD = make("forward", SDL_SCANCODE_KP_PLUS, "forward");
		OPEN_SETTINGS = make("settings", SDL_SCANCODE_KP_DIVIDE, "settings");
		SHORT_PREV = make("shorts_prev", SDL_SCANCODE_KP_7, "shorts_prev");   // YouTube Shorts / TikTok / Reels: previous video
		SHORT_NEXT = make("shorts_next", SDL_SCANCODE_KP_9, "shorts_next");   // ... next video
		SIZE = make("size", SDL_SCANCODE_KP_1, "size");                       // small -> normal -> big -> small
	}

	// ---- Ctrl / Shift / Alt together with a key (the game itself only knows single keys)

	public static final int MOD_SHIFT = 1, MOD_CTRL = 2, MOD_ALT = 4;   // the mod's OWN numbers (saved in the settings), not SDL's

	/** The extra keys that must be held for this hotkey (0 = none: then the hotkey works with any extra keys held). */
	public static int requiredMods(KeyMapping kb) {
		return PlayerConfig.get().modsOf(kb.getName());
	}

	public static void setRequiredMods(KeyMapping kb, int mods) {
		PlayerConfig.get().setModsOf(kb.getName(), mods);
	}

	/** @param held Ctrl / Shift / Alt that are held down right now */
	public static boolean modsOk(KeyMapping kb, int held) {
		int need = requiredMods(kb);
		return need == 0 || (held & need) == need;
	}

	/** Ctrl / Shift / Alt that are held down right now (when no screen is open). */
	public static int heldMods() {
		int m = 0;   // 26.3: isKeyDown takes only the key number (no window any more)
		if (InputConstants.isKeyDown(InputConstants.KEY_LSHIFT) || InputConstants.isKeyDown(InputConstants.KEY_RSHIFT)) m |= MOD_SHIFT;
		if (InputConstants.isKeyDown(InputConstants.KEY_LCONTROL) || InputConstants.isKeyDown(InputConstants.KEY_RCONTROL)) m |= MOD_CTRL;
		if (InputConstants.isKeyDown(InputConstants.KEY_LALT) || InputConstants.isKeyDown(InputConstants.KEY_RALT)) m |= MOD_ALT;
		return m;
	}

	/** Ctrl / Shift / Alt (the mod's own numbers) for a key event inside a screen. */
	public static int mods(KeyEvent input) {
		return heldMods();
	}

	/** "Ctrl + Shift + " (empty when there are no extra keys). */
	public static String modsPrefix(int mods) {
		StringBuilder sb = new StringBuilder();
		if ((mods & MOD_CTRL) != 0) sb.append("Ctrl + ");
		if ((mods & MOD_SHIFT) != 0) sb.append("Shift + ");
		if ((mods & MOD_ALT) != 0) sb.append("Alt + ");
		return sb.toString();
	}

	/** The name of the key of a hotkey with its extra keys, for example "Ctrl + Num 7". */
	public static Component label(KeyMapping kb) {
		return Component.literal(modsPrefix(requiredMods(kb))).append(kb.getTranslatedKeyMessage());
	}

	/** 1.21.9+: matchesKey wants a KeyEvent object. The rest of the mod still works with the plain numbers. */
	public static boolean matches(KeyMapping kb, int key, int scancode) {
		return kb.matches(new KeyEvent(key, scancode, 0));
	}

	/** Called every game tick. */
	public static void tick(Minecraft mc) {
		for (KeyMapping kb : ALL) {
			while (kb.consumeClick()) {
				if (modsOk(kb, heldMods())) run(kb, mc);
			}
		}
	}

	// ---- the same hotkeys inside screens (the game itself only reads them when no screen is open)

	private static int heldKey = -1;

	public static void resetHeld() {
		heldKey = -1;
	}

	public static void screenKeyReleased(int key) {
		if (key == heldKey) heldKey = -1;
	}

	/**
	 * Where the hotkeys may be used inside a screen: EVERY screen (pause menu, title screen, inventory, advancements, settings ...),
	 * no matter if the player is pinned or super pinned. Only the screens where keys mean something else are left alone
	 * (chat, typing, changing keys, the player settings itself). A text box with the cursor in it is checked separately.
	 */
	public static boolean screenKeysAllowed(Screen s) {
		return MediaRiftClient.menuUiAllowed(s);
	}

	/** @return true if the key is one of ours (then the screen must not use it). */
	public static boolean screenKeyPressed(int key, int scancode, int modifiers) {
		for (KeyMapping kb : ALL) {
			if (matches(kb, key, scancode) && modsOk(kb, modifiers & 7)) {
				boolean repeat = key == heldKey;   // the key is held down: only the volume keys repeat
				heldKey = key;
				if (!repeat || kb == VOL_UP || kb == VOL_DOWN) run(kb, Minecraft.getInstance());
				return true;
			}
		}
		return false;
	}

	/** One hotkey was pressed (in the game or in an allowed screen). */
	private static void run(KeyMapping kb, Minecraft mc) {
		BrowserPlayer ui = BrowserPlayer.get();
		if (kb == TOGGLE_WINDOW) {
			PlayerConfig c = PlayerConfig.get();
			if (c.pinned || c.superPinned) {
				ui.toggleWindow();
			} else if (mc.gui != null) {
				// the window is only visible when pinned: tell the player what to do
				mc.gui.hud.setOverlayMessage(Component.translatable("mediarift.msg.need_pin", label(PIN)), false);
			}
		} else if (kb == OPEN_SETTINGS) {
			// opens the settings of the mod at once (no need to go through Mod Menu); "Done" returns to where you were
			mc.gui.setScreen(new MusicConfigScreen(mc.gui.screen()));
		} else if (kb == PLAY_PAUSE) {
			ui.doAction(1);
		} else if (kb == PREV) {
			ui.doAction(0);
		} else if (kb == NEXT) {
			ui.doAction(2);
		} else if (kb == VOL_UP) {
			ui.changeVolume(5);
			ui.flashVolume();   // the volume slider shows up for a moment
		} else if (kb == VOL_DOWN) {
			ui.changeVolume(-5);
			ui.flashVolume();
		} else if (kb == PIN) {
			PlayerConfig c = PlayerConfig.get();
			c.setPinMode((c.pinMode() + 1) % 3);
			c.save();
			ui.flashPin();      // the pin button shows up for a moment
		} else if (kb == BACK) {
			int r = ui.goBack();
			if (mc.gui != null) {
				String key = r == 0 ? "mediarift.msg.back" : (r == 1 ? "mediarift.msg.reload.none" : "mediarift.msg.back.none");
				mc.gui.hud.setOverlayMessage(Component.translatable(key), false);
			}
		} else if (kb == FORWARD) {
			int r = ui.goForward();
			if (mc.gui != null) {
				String key = r == 0 ? "mediarift.msg.forward" : (r == 1 ? "mediarift.msg.reload.none" : "mediarift.msg.forward.none");
				mc.gui.hud.setOverlayMessage(Component.translatable(key), false);
			}
		} else if (kb == RELOAD) {
			boolean ok = ui.reloadPage();
			if (mc.gui != null) {
				mc.gui.hud.setOverlayMessage(Component.translatable(ok ? "mediarift.msg.reload" : "mediarift.msg.reload.none"), false);
			}
		} else if (kb == SIZE) {
			int step = PlayerConfig.get().cycleSize();   // 0 = small, 1 = normal, 2 = big
			if (mc.gui != null) mc.gui.hud.setOverlayMessage(Component.translatable("mediarift.msg.size." + step), false);
		} else if (kb == SHORT_PREV) {
			ui.shortsStep(-1);
		} else if (kb == SHORT_NEXT) {
			ui.shortsStep(1);
		}
	}
}
