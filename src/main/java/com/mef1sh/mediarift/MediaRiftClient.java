package com.mef1sh.mediarift;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import com.mojang.blaze3d.platform.InputConstants;
import com.mef1sh.mediarift.stream.LiveStreamService;
import com.mef1sh.mediarift.stream.ToolInstaller;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class MediaRiftClient implements ClientModInitializer {
	/** true while the player was inside a world on the previous tick. */
	private static boolean wasInWorld = false;
	/** The "download streamlink + ffmpeg" loading screen is offered only once per game start. */
	private static boolean toolsChecked = false;

	public static final Logger LOGGER = LoggerFactory.getLogger("mediarift");

	/** /sb <name>  ->  opens the site and remembers it. */
	private static LiteralArgumentBuilder<FabricClientCommandSource> site(String name, String url) {
		return ClientCommands.literal(name).executes(ctx -> {
			ctx.getSource().sendFeedback(Component.literal(BrowserPlayer.get().openSite(url)));
			return 1;
		});
	}

	/** /sb ... : the sites and streams (in this order in the hints). */
	private static final String[] SB_ACTIONS = {"twitch", "kick", "youtube", "ytmusic", "live", "url"};
	/** /psb ... : the tools of the mod (debug is not in the list on purpose: only for finding problems). */
	private static final String[] PSB_ACTIONS = {"reset", "reload", "install"};

	/** Hints for a command. Normally the game sorts them A-Z; building the list by hand keeps our order. */
	private static SuggestionProvider<FabricClientCommandSource> hints(String[] actions) {
		return (ctx, builder) -> {
			String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
			StringRange range = StringRange.between(builder.getStart(), builder.getInput().length());
			List<Suggestion> list = new ArrayList<>();
			for (String a : actions) {
				if (a.startsWith(typed)) list.add(new Suggestion(range, a));
			}
			return CompletableFuture.completedFuture(new Suggestions(range, list));
		};
	}

	private static int sbUsage(FabricClientCommandSource src) {
		src.sendFeedback(Component.literal("/sb twitch | /sb kick | /sb youtube | /sb ytmusic | /sb live [address|stop|status] (stream through streamlink + ffmpeg) | /sb url <address>"
				+ "      Tools: /psb reset (log out of all sites) | /psb reload | /psb install (downloads streamlink + ffmpeg)"));
		return 1;
	}

	private static int psbUsage(FabricClientCommandSource src) {
		src.sendFeedback(Component.literal("/psb reset (log out of all sites) | /psb reload | /psb install (downloads streamlink + ffmpeg)"));
		return 1;
	}

	private static int runSb(FabricClientCommandSource src, String action, String address) {
		String a = action.toLowerCase(Locale.ROOT);
		String url = null;
		if (a.equals("live")) {
			return runLive(src, address);
		} else if (a.equals("kick")) {
			url = "https://kick.com";
		} else if (a.equals("twitch")) {
			url = "https://www.twitch.tv";
		} else if (a.equals("ytmusic")) {
			url = "https://music.youtube.com";
		} else if (a.equals("youtube")) {
			url = "https://www.youtube.com";
		} else if (a.equals("url") && address != null && !address.isBlank()) {
			url = address.trim();
			if (!url.startsWith("http")) url = "https://" + url;
		}
		if (url == null) return sbUsage(src);
		src.sendFeedback(Component.literal(BrowserPlayer.get().openSite(url)));
		return 1;
	}

	private static int runPsb(FabricClientCommandSource src, String action, String address) {
		String a = action.toLowerCase(Locale.ROOT);
		if (a.equals("reload")) {
			src.sendFeedback(Component.literal(BrowserPlayer.get().reload()));
			return 1;
		} else if (a.equals("reset")) {
			src.sendFeedback(Component.literal(BrowserPlayer.get().clearLogins()));
			return 1;
		} else if (a.equals("install")) {
			src.sendFeedback(Component.literal(LiveTools.installInBackground()));
			return 1;
		} else if (a.equals("debug")) {
			return runDebug(src, address);   // not in the hints on purpose: only for finding problems
		}
		return psbUsage(src);
	}

	/** /sb live [address | stop | status | install]: a stream through streamlink + ffmpeg, right away. */
	private static int runLive(FabricClientCommandSource src, String arg) {
		String a = arg == null ? "" : arg.trim();
		String low = a.toLowerCase(Locale.ROOT);
		BrowserPlayer bp = BrowserPlayer.get();
		if (low.equals("install")) {
			src.sendFeedback(Component.literal(LiveTools.installInBackground()));
			return 1;
		}
		if (low.equals("stop")) {
			src.sendFeedback(Component.literal(bp.stopLive()));
			return 1;
		}
		if (low.equals("status")) {
			// looking for the programs starts them, so do it away from the game thread
			Thread t = new Thread(() -> LiveTools.chat(LiveStreamService.get().describe(LiveTools.options(), LiveTools.toolsDir())),
					"MediaRift-LiveStatus");
			t.setDaemon(true);
			t.start();
			return 1;
		}
		String url = a.isEmpty() ? bp.currentPageUrl() : (a.startsWith("http") ? a : "https://" + a);
		if (url == null || url.isEmpty()) {
			src.sendFeedback(Component.literal("Open a stream in the player first, or type /sb live <address of the stream>"));
			return 1;
		}
		src.sendFeedback(Component.literal(bp.startLiveManual(url)));
		return 1;
	}

	/** /psb debug (not in the hints): what this browser really can play + which programs were found. */
	private static int runDebug(FabricClientCommandSource src, String arg) {
		if (arg != null && arg.trim().equalsIgnoreCase("install")) {
			src.sendFeedback(Component.literal(LiveTools.installInBackground()));
			return 1;
		}
		BrowserPlayer.get().debugCodecs();
		Thread t = new Thread(() -> LiveTools.chat(LiveStreamService.get().describe(LiveTools.options(), LiveTools.toolsDir())),
				"MediaRift-LiveStatus");
		t.setDaemon(true);
		t.start();
		return 1;
	}

	/** Adds the "MediaRift" volume slider to Minecraft's Music & Sounds screen. */
	private static void addVolumeSlider(net.minecraft.client.gui.screens.Screen screen) {
		PlayerConfig cfg = PlayerConfig.get();
		// 26.1: a plain slider in the bottom-left corner of the Music & Sounds screen
		Screens.getWidgets(screen).add(new IntSlider(10, screen.height - 27, 150, 20, Component.literal("MediaRift"), 0, 100, "%",
				cfg.volume, v -> {
					cfg.volume = v;
					cfg.save();
					BrowserPlayer.get().applyVolume();
				}));
	}

	// ------------------------------------------------------------------ the "T" panel in every menu

	/** The screen on which the player panel was opened with T (null = no panel). */
	private static Screen menuUiScreen = null;

	/** Screens where T must stay a normal key (typing, changing keys, the player settings itself). */
	private static boolean menuUiAllowed(Screen s) {
		// sign and book screens are found by their class name (since 26.1 the game is not scrambled, the names are real)
		String n = s.getClass().getSimpleName();
		return !(s instanceof ChatScreen || s instanceof MusicConfigScreen || s instanceof ToolsDownloadScreen
				|| s instanceof KeyBindsScreen || n.contains("SignEditScreen") || n.contains("BookEditScreen"));
	}

	/** Is the cursor in a text box right now (search, name, address ...)? Then T is a letter, not the panel key. */
	private static boolean typingInTextField(Screen s) {
		GuiEventListener e = s.getFocused();
		for (int i = 0; i < 8 && e instanceof net.minecraft.client.gui.components.events.ContainerEventHandler pe && pe.getFocused() != null; i++) e = pe.getFocused();
		return e instanceof EditBox;
	}

	/** "Behind the buttons": the buttons of the screen are drawn once more on top of the player window. */
	private static void redrawWidgets(Screen s, DrawContext ctx, int mouseX, int mouseY, float delta) {
		for (GuiEventListener e : s.children()) {
			if (e instanceof Renderable d) d.extractRenderState(ctx.raw(), mouseX, mouseY, delta);
		}
	}

	/** In "behind the buttons" mode a click on a button belongs to the button, not to the player window that is under it. */
	private static boolean playerGetsMouse(Screen s, double x, double y) {
		if (!PlayerConfig.get().behindMenus) return true;
		for (GuiEventListener e : s.children()) {
			if (e instanceof AbstractWidget w && w.visible && w.isMouseOver(x, y)) return false;
		}
		return true;
	}

	private static void openMenuUi(Screen s) {
		menuUiScreen = s;
		BrowserPlayer.get().reset();   // same start as when the chat opens
	}

	private static void closeMenuUi() {
		if (menuUiScreen == null) return;
		menuUiScreen = null;
		BrowserPlayer.get().onChatClosed();
	}

	/** @return false = the key was used by the player (the screen must not see it). */
	private static boolean menuKeyPressed(Screen s, KeyEvent input) {
		int key = input.key(), scancode = input.keycode(), modifiers = input.modifiers();   // 1.21.9+: keys come as one object
		Minecraft mc = Minecraft.getInstance();
		BrowserPlayer ui = BrowserPlayer.get();
		if (menuUiScreen == s) {
			if (ui.isFocused()) {
				// the browser has the keyboard: every key goes to the page, Esc only gives the keyboard back
				ui.keyPressed(key, scancode, modifiers);
				return false;
			}
			if (key == InputConstants.KEY_ESCAPE || ModKeys.matches(mc.options.keyChat, key, scancode)) {
				closeMenuUi();   // Esc / T closes the panel (the next Esc closes the menu itself)
				return false;
			}
		} else if (ModKeys.matches(mc.options.keyChat, key, scancode) && !typingInTextField(s)) {
			openMenuUi(s);
			return false;
		}
		// the normal hotkeys in the pause menu / title screen
		if (s instanceof PauseScreen || s instanceof TitleScreen) {
			return !(ModKeys.screenKeysAllowed(s) && ModKeys.screenKeyPressed(key, scancode, ModKeys.mods(input)));
		}
		return true;
	}

	@Override
	public void onInitializeClient() {
		LegacyMigration.run();   // files of the old name (Music Browser) -> new names, once
		PlayerConfig.get();      // load the settings file
		LiveStreamService.log = msg -> LOGGER.info("[live] {}", msg);
		ModKeys.register(); // hotkeys

		// leaving the world (to the title screen) stops the music
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			boolean inWorld = mc.level != null;
			if (wasInWorld && !inWorld && !PlayerConfig.get().superPinned) BrowserPlayer.get().pauseAll();
			wasInWorld = inWorld;
			ModKeys.tick(mc);

			// first time the title screen shows: download streamlink + ffmpeg if they are missing (like the Chromium library download)
			if (!toolsChecked && mc.gui.screen() instanceof TitleScreen) {
				toolsChecked = true;
				PlayerConfig cfg = PlayerConfig.get();
				if (cfg.liveEnabled && cfg.liveAutoDownload && ToolInstaller.canAutoInstall()
						&& ToolInstaller.needsDownload(LiveTools.toolsDir(), LiveTools.options())) {
					mc.gui.setScreen(new ToolsDownloadScreen(mc.gui.screen()));
				}
			}
		});

		// pinned player: drawn while the chat is closed
		// 26.1: HUD parts are registered by name (HudRenderCallback is gone)
		HudElementRegistry.addLast(net.minecraft.resources.Identifier.fromNamespaceAndPath("mediarift", "player"), (raw, deltaTracker) -> {
			DrawContext ctx = new DrawContext(raw);   // our small helper in front of Minecraft's GuiGraphicsExtractor
			Minecraft mc = Minecraft.getInstance();
			PlayerConfig cfg = PlayerConfig.get();
			// F3 (the debug screen) hides the window
			if (mc.gui.screen() != null || mc.gui.hud.isHidden() || mc.player == null || mc.debugEntries.isOverlayVisible()) return;
			int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
			// also while something is still sliding / folding away (after unpinning or closing the chat)
			BrowserPlayer.get().setLift(0f);   // the game screen is drawn normally, no screen above it
			if (cfg.pinned || cfg.superPinned || BrowserPlayer.get().isBusy()) BrowserPlayer.get().renderHud(ctx, sw, sh);
			BrowserPlayer.get().renderFlashes(ctx, sw, sh, false);   // short hints for volume / pin hotkeys
		});

		// /sb and /psb: the hints in the chat come in OUR order, not A-Z
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommands.literal("sb")
					.executes(ctx -> sbUsage(ctx.getSource()))
					.then(ClientCommands.argument("action", StringArgumentType.word())
							.suggests(hints(SB_ACTIONS))
							.executes(ctx -> runSb(ctx.getSource(), StringArgumentType.getString(ctx, "action"), null))
							.then(ClientCommands.argument("address", StringArgumentType.greedyString())
									.executes(ctx -> runSb(ctx.getSource(),
											StringArgumentType.getString(ctx, "action"),
											StringArgumentType.getString(ctx, "address"))))));
			dispatcher.register(ClientCommands.literal("psb")
					.executes(ctx -> psbUsage(ctx.getSource()))
					.then(ClientCommands.argument("action", StringArgumentType.word())
							.suggests(hints(PSB_ACTIONS))
							.executes(ctx -> runPsb(ctx.getSource(), StringArgumentType.getString(ctx, "action"), null))
							.then(ClientCommands.argument("address", StringArgumentType.greedyString())
									.executes(ctx -> runPsb(ctx.getSource(),
											StringArgumentType.getString(ctx, "action"),
											StringArgumentType.getString(ctx, "address"))))));
		});

		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			ModKeys.resetHeld();
			if (screen.getClass().getSimpleName().equals("SoundOptionsScreen")) addVolumeSlider(screen);

			// letters typed while the browser has the keyboard go to the page (login, search), not to the chat / menu.
			// (before 26.1 this was done with a mixin into the keyboard class; Fabric has a ready event for it now)
			ScreenKeyboardEvents.allowCharType(screen).register((s, event) -> {
				int codePoint = event.codepoint();
				return !(Character.isBmpCodePoint(codePoint) && BrowserPlayer.get().charTyped((char) codePoint, 0));
			});

			if (!(screen instanceof ChatScreen)) {
				// super pin: the player is drawn on top of every other screen (title screen, settings, ...)
				if (!(screen instanceof MusicConfigScreen)) {
					BrowserPlayer menuUi = BrowserPlayer.get();
					boolean menuOk = menuUiAllowed(screen);
					// a screen change ends the "T" panel of the screen before
					if (menuUiScreen != null && menuUiScreen != screen) closeMenuUi();

					ScreenEvents.afterExtract(screen).register((s, raw, mouseX, mouseY, delta) -> {
						DrawContext ctx = new DrawContext(raw);
						boolean behind = PlayerConfig.get().behindMenus;
						// in front (normal): the player is drawn above everything, also above the button texts. Behind: normal order.
						menuUi.setLift(behind ? 0f : BrowserPlayer.LIFT);
						if (menuUiScreen == s) {
							// the "T" panel is open: the player looks and works exactly like in the chat
							menuUi.render(ctx, mouseX, mouseY, s.width, s.height);
							menuUi.renderFlashes(ctx, s.width, s.height, true);
							if (behind) redrawWidgets(s, ctx, mouseX, mouseY, delta);
							return;
						}
						// F3 (the debug screen) hides the window
						if (Minecraft.getInstance().debugEntries.isOverlayVisible()) return;
						// green pin + inside a world: also on top of advancements, inventory / recipe book and other game screens
						boolean inWorld = Minecraft.getInstance().player != null;
						if (PlayerConfig.get().superPinned || menuUi.isBusy() || (PlayerConfig.get().pinned && inWorld)) {
							menuUi.renderHud(ctx, s.width, s.height);
							if (behind) redrawWidgets(s, ctx, mouseX, mouseY, delta);
						}
						// the short hints (volume / pin hotkeys) also show in the pause menu and the title screen
						if (ModKeys.screenKeysAllowed(s)) menuUi.renderFlashes(ctx, s.width, s.height, false);
					});

					if (menuOk) {
						// T (the chat key) opens the player panel on every screen; T or Esc closes it again
						ScreenEvents.remove(screen).register(s -> {
							if (menuUiScreen == s) closeMenuUi();
						});
						ScreenMouseEvents.allowMouseClick(screen).register((s, click) ->
								!(menuUiScreen == s && playerGetsMouse(s, click.x(), click.y()) && menuUi.mouseClicked(click.x(), click.y(), click.button())));
						ScreenMouseEvents.allowMouseRelease(screen).register((s, click) ->
								!(menuUiScreen == s && playerGetsMouse(s, click.x(), click.y()) && menuUi.mouseReleased(click.x(), click.y(), click.button())));
						ScreenMouseEvents.allowMouseScroll(screen).register((s, mouseX, mouseY, horizontal, vertical) ->
								!(menuUiScreen == s && playerGetsMouse(s, mouseX, mouseY) && menuUi.mouseScrolled(mouseX, mouseY, vertical)));
						ScreenKeyboardEvents.allowKeyPress(screen).register(MediaRiftClient::menuKeyPressed);
						ScreenKeyboardEvents.allowKeyRelease(screen).register((s, input) -> {
							ModKeys.screenKeyReleased(input.key());
							if (menuUiScreen == s && menuUi.isFocused()) {
								menuUi.keyReleased(input.key(), input.keycode(), input.modifiers());
								return false;
							}
							return true;
						});
					}
				}
				return;
			}

			BrowserPlayer ui = BrowserPlayer.get();
			ui.reset();

			ScreenEvents.afterExtract(screen).register((s, raw, mouseX, mouseY, delta) -> {
				DrawContext ctx = new DrawContext(raw);
				ui.setLift(BrowserPlayer.LIFT);
				ui.render(ctx, mouseX, mouseY, s.width, s.height);
				ui.renderFlashes(ctx, s.width, s.height, true);   // a fading hint keeps fading when the chat opens
			});
			ScreenEvents.remove(screen).register(s -> ui.onChatClosed());

			// returning false = "cancel this event", so the chat does not react to it
			ScreenMouseEvents.allowMouseClick(screen).register((s, click) ->
					!ui.mouseClicked(click.x(), click.y(), click.button()));
			ScreenMouseEvents.allowMouseRelease(screen).register((s, click) ->
					!ui.mouseReleased(click.x(), click.y(), click.button()));
			ScreenMouseEvents.allowMouseScroll(screen).register((s, mouseX, mouseY, horizontal, vertical) ->
					!ui.mouseScrolled(mouseX, mouseY, vertical));
			ScreenKeyboardEvents.allowKeyPress(screen).register((s, input) ->
					!ui.keyPressed(input.key(), input.keycode(), input.modifiers()));
			ScreenKeyboardEvents.allowKeyRelease(screen).register((s, input) ->
					!ui.keyReleased(input.key(), input.keycode(), input.modifiers()));
		});

		LOGGER.info("MediaRift loaded");
	}
}
