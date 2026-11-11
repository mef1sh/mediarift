package com.mef1sh.mediarift;

import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.List;

/**
 * The settings screen of the mod (Mod Menu -> MediaRift -> settings button). Three tabs.
 * All texts come from the language files (assets/mediarift/lang): English and Russian.
 */
public class MusicConfigScreen extends Screen {
	/** Which tab is open: 0 = look and place, 1 = buttons and pins, 2 = keys. Remembered while the game runs. */
	private static int page = 0;

	private static final String T = "mediarift.config.";

	private final Screen parent;
	/** The hotkey that is waiting for a new key (null = none). */
	private KeyMapping rebinding = null;
	/** A modifier key (Ctrl / Shift / Alt) was pressed while waiting for a new hotkey: we wait for the real key. */
	private int pendingMod = -1;

	public MusicConfigScreen(Screen parent) {
		super(Component.literal("MediaRift"));
		this.parent = parent;
	}

	private static Component resolutionText(PlayerConfig c) {
		// 1080 with the VP8 codec: streams get 720 only (the label says so)
		if (c.resolution >= 1000 && "vp8".equalsIgnoreCase(c.liveCodec)) return Component.translatable("mediarift.config.resolution.1080.vp8");
		return Component.translatable("mediarift.config.resolution." + c.resolution);
	}

	private static Component codecText(PlayerConfig c) {
		return Component.translatable("mediarift.config.codec." + ("vp8".equalsIgnoreCase(c.liveCodec) ? "vp8" : "vp9"));
	}

	private static Component behindText(PlayerConfig c) {
		return Component.translatable("mediarift.config.behind." + (c.behindMenus ? 1 : 0));
	}

	private static Component tr(String key) {
		return Component.translatable(T + key);
	}

	/** The resize button can make the window bigger / smaller than the sliders go: show the nearest slider value. */
	private static int sliderInit(float percent) {
		return Math.max(50, Math.min(200, Math.round(percent)));
	}

	private void setCorner(int x, int y) {
		PlayerConfig c = PlayerConfig.get();
		c.posX = x;
		c.posY = y;
		rebuildWidgets(); // rebuild the screen so the sliders show the new numbers
	}

	private void preset(int n) {
		PlayerConfig.get().applyPreset(n);
		rebuildWidgets();
	}

	private void toggle(int x, int y, int cw, boolean value, String name, String tip,
						java.util.function.Consumer<Boolean> setter) {
		CycleButton.Builder<Boolean> b = CycleButton.onOffBuilder(value);
		if (tip != null) b = b.withTooltip(v -> Tooltip.create(tr(tip)));
		addRenderableWidget(b.create(x, y, cw, 20, tr(name), (btn, v) -> setter.accept(v)));
	}

	private void tab(int x, int w, int index, String name) {
		Button b = Button.builder(tr(name), btn -> {
			page = index;
			rebinding = null;
			rebuildWidgets();
		}).bounds(x, 24, w, 20).build();
		b.active = page != index;
		addRenderableWidget(b);
	}

	@Override
	protected void init() {
		PlayerConfig c = PlayerConfig.get();
		int lx = width / 2 - 155;
		int rx = width / 2 + 5;
		int cw = 150;
		int rh = page == 0 ? 21 : 22;   // the first page has the most rows: a bit tighter so everything fits

		// tabs
		tab(lx, 100, 0, "tab.look");
		tab(lx + 105, 100, 1, "tab.buttons");
		tab(lx + 210, 100, 2, "tab.keys");

		int y = 50;

		if (page == 0) {
			// row 1: overall size + volume
			addRenderableWidget(new IntSlider(lx, y, cw, 20, tr("size"), 50, 200, "%", c.sizePercent,
					v -> c.sizePercent = v));
			addRenderableWidget(new IntSlider(rx, y, cw, 20, tr("volume"), 0, 100, "%", c.volume, v -> {
				c.volume = v;
				BrowserPlayer.get().applyVolume();
			}));
			y += rh;

			// row 2: width and height separately
			addRenderableWidget(new IntSlider(lx, y, cw, 20, tr("width"), 50, 200, "%", sliderInit(c.widthPercent),
					v -> c.widthPercent = v));
			addRenderableWidget(new IntSlider(rx, y, cw, 20, tr("height"), 50, 200, "%", sliderInit(c.heightPercent),
					v -> c.heightPercent = v));
			y += rh;

			// row 3: page zoom + recommended sets
			// the page zoom is shown in percent (100 % = normal page size); we store it as a CEF zoom level
			addRenderableWidget(new IntSlider(lx, y, cw, 20, tr("zoom"), 50, 120, "%",
					(int) Math.round(100 * Math.pow(1.2, c.zoomTenths / 10.0)),
					v -> c.zoomTenths = (int) Math.round(Math.log(v / 100.0) / Math.log(1.2) * 10)));
			addRenderableWidget(Button.builder(tr("preset.small"), b -> preset(0))
					.bounds(rx, y, 48, 20).tooltip(Tooltip.create(tr("preset.small.tip"))).build());
			addRenderableWidget(Button.builder(tr("preset.normal"), b -> preset(1))
					.bounds(rx + 51, y, 48, 20).tooltip(Tooltip.create(tr("preset.normal.tip"))).build());
			addRenderableWidget(Button.builder(tr("preset.big"), b -> preset(2))
					.bounds(rx + 102, y, 48, 20).tooltip(Tooltip.create(tr("preset.big.tip"))).build());
			y += rh;

			// row 4: place (anywhere on the screen)
			addRenderableWidget(new IntSlider(lx, y, cw, 20, tr("posx"), 0, 100, "%", Math.round(c.posX), v -> c.posX = v));
			addRenderableWidget(new IntSlider(rx, y, cw, 20, tr("posy"), 0, 100, "%", c.posY, v -> c.posY = v));
			y += rh;

			// row 5: corners
			addRenderableWidget(Button.builder(tr("corner.tl"), b -> setCorner(0, 0))
					.bounds(lx, y, 73, 20).build());
			addRenderableWidget(Button.builder(tr("corner.tr"), b -> setCorner(100, 0))
					.bounds(lx + 77, y, 73, 20).build());
			addRenderableWidget(Button.builder(tr("corner.bl"), b -> setCorner(0, 100))
					.bounds(rx, y, 73, 20).build());
			addRenderableWidget(Button.builder(tr("corner.br"), b -> setCorner(100, 100))
					.bounds(rx + 77, y, 73, 20).build());
			y += rh;
			// last row: the resolution of streams (Twitch, Kick, YouTube live) and of YouTube videos
			Button resBtn = Button.builder(resolutionText(c), b -> {
				c.resolution = c.resolution >= 1000 ? 720 : (c.resolution >= 600 ? 480 : 1080);   // 1080 -> 720 -> 480 -> 1080
				b.setMessage(resolutionText(c));
				BrowserPlayer.get().applyResolution();
			}).bounds(lx, y, rx + cw - lx, 20).tooltip(Tooltip.create(tr("resolution.tip"))).build();
			addRenderableWidget(resBtn);
			y += rh;
			// the codec of streams (Twitch, Kick, YouTube live): VP9 by default, VP8 for very weak computers. Streams only.
			addRenderableWidget(Button.builder(codecText(c), b -> {
				c.liveCodec = "vp8".equalsIgnoreCase(c.liveCodec) ? "vp9" : "vp8";
				b.setMessage(codecText(c));
				resBtn.setMessage(resolutionText(c));   // 1080 + VP8 = streams get 720
			}).bounds(lx, y, rx + cw - lx, 20).tooltip(Tooltip.create(tr("codec.tip"))).build());
			y += rh;
			// where the player window is drawn in the pause menu, the title screen and other menus: in front of the buttons or behind them
			addRenderableWidget(Button.builder(behindText(c), b -> {
				c.behindMenus = !c.behindMenus;
				b.setMessage(behindText(c));
			}).bounds(lx, y, rx + cw - lx, 20).tooltip(Tooltip.create(tr("behind.tip"))).build());
			y += rh;
			// how visible the window (the site / video) is, and how visible the bar, the pin, the buttons and the volume slider are
			IntSlider winOp = new IntSlider(lx, y, cw, 20, tr("opacity.window"), 10, 100, "%", c.windowOpacity,
					v -> c.windowOpacity = v);
			winOp.setTooltip(Tooltip.create(tr("opacity.window.tip")));
			addRenderableWidget(winOp);
			IntSlider uiOp = new IntSlider(rx, y, cw, 20, tr("opacity.ui"), 10, 100, "%", c.uiOpacity,
					v -> c.uiOpacity = v);
			uiOp.setTooltip(Tooltip.create(tr("opacity.ui.tip")));
			addRenderableWidget(uiOp);
		} else if (page == 1) {
			// row 1: the whole bottom row on / off + hide it without chat
			toggle(lx, y, cw, c.showButtonRow, "buttonrow", "buttonrow.tip", v -> c.showButtonRow = v);
			toggle(rx, y, cw, c.hideButtonsWhenChatClosed, "hidebuttons", "hidebuttons.tip",
					v -> c.hideButtonsWhenChatClosed = v);
			y += rh;

			// row 2: each button
			toggle(lx, y, cw, c.showPrev, "prev", null, v -> c.showPrev = v);
			toggle(rx, y, cw, c.showPlay, "play", null, v -> c.showPlay = v);
			y += rh;

			// row 3
			toggle(lx, y, cw, c.showNext, "next", null, v -> c.showNext = v);
			toggle(rx, y, cw, c.showVolumeBar, "volbar", "volbar.tip", v -> c.showVolumeBar = v);
			y += rh;

			// row 4
			toggle(lx, y, cw, c.hideVolumeBarWhenChatClosed, "hidevol", "hidevol.tip",
					v -> c.hideVolumeBarWhenChatClosed = v);
			toggle(rx, y, cw, c.showTicker, "ticker", "ticker.tip", v -> c.showTicker = v);
			y += rh;

			// row 5: hide the bar without chat + the pin (3 states)
			toggle(lx, y, cw, c.hideTickerWhenChatClosed, "hideticker", "hideticker.tip",
					v -> c.hideTickerWhenChatClosed = v);
			addRenderableWidget(CycleButton.<Integer>builder(
							v -> tr(v == 2 ? "pin.super" : (v == 1 ? "pin.green" : "pin.off")), () -> c.pinMode())   // the start value
					.withValues(0, 1, 2)
					.withTooltip(v -> Tooltip.create(tr("pin.tip")))
					.create(rx, y, cw, 20, tr("pin"), (b, v) -> c.setPinMode(v)));
		} else {
			// hotkeys: two columns, click a button and then press the new key
			List<KeyMapping> keys = ModKeys.ALL;
			for (int i = 0; i < keys.size(); i++) {
				KeyMapping kb = keys.get(i);
				int x = (i % 2 == 0) ? lx : rx;
				int yy = y + (i / 2) * rh;
				Component keyText = kb == rebinding
						? Component.empty().append(tr("keys.waiting")).withStyle(ChatFormatting.YELLOW)
						: ModKeys.label(kb);
				Component label = Component.translatable(ModKeys.SHORT_NAMES.get(i)).append(Component.literal(": ")).append(keyText);
				addRenderableWidget(Button.builder(label, b -> {
					rebinding = kb;
					rebuildWidgets();
				}).bounds(x, yy, cw, 20).build());
			}
			// the free slot after the last key: reset all keys
			int resetIndex = keys.size();
			int rxx = (resetIndex % 2 == 0) ? lx : rx;
			int ryy = y + (resetIndex / 2) * rh;
			addRenderableWidget(Button.builder(tr("keys.reset"), b -> {
				for (KeyMapping kb : ModKeys.ALL) {
					kb.setKey(kb.getDefaultKey());
					ModKeys.setRequiredMods(kb, 0);
				}
				PlayerConfig.get().save();
				KeyMapping.resetMapping();
				if (minecraft != null) minecraft.options.save();
				rebinding = null;
				rebuildWidgets();
			}).bounds(rxx, ryy, cw, 20).build());
		}

		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
				.bounds(width / 2 - 100, height - 27, 200, 20).build());
	}

	// ------------------------------------------------------------------ changing a hotkey

	private void bindKey(InputConstants.Key key, int mods) {
		rebinding.setKey(key);
		ModKeys.setRequiredMods(rebinding, key == InputConstants.UNKNOWN ? 0 : mods);
		PlayerConfig.get().save();
		KeyMapping.resetMapping();
		if (minecraft != null) minecraft.options.save();
		rebinding = null;
		pendingMod = -1;
		rebuildWidgets();
	}

	private static boolean isModifierKey(int code) {
		return code == InputConstants.KEY_LSHIFT || code == InputConstants.KEY_RSHIFT
				|| code == InputConstants.KEY_LCONTROL || code == InputConstants.KEY_RCONTROL
				|| code == InputConstants.KEY_LALT || code == InputConstants.KEY_RALT;
	}

	/** A modifier key that was pressed and let go again without another key: that key alone is the new hotkey. */
	@Override
	public boolean keyReleased(KeyEvent input) {
		int keyCode = input.key(), scanCode = input.keycode();   // 1.21.9+: keys come as one object
		if (rebinding != null && pendingMod == keyCode) {
			bindKey(InputConstants.getKey(input), 0);
			return true;
		}
		return super.keyReleased(input);
	}

	@Override
	public boolean keyPressed(KeyEvent input) {
		int keyCode = input.key(), scanCode = input.keycode(), modifiers = ModKeys.mods(input);   // 26.3: our own Ctrl/Shift/Alt numbers
		if (rebinding != null) {
			if (keyCode == InputConstants.KEY_ESCAPE) {
				bindKey(InputConstants.UNKNOWN, 0);
			} else if (isModifierKey(keyCode)) {
				pendingMod = keyCode;   // maybe Ctrl + something: wait for the next key
			} else {
				// Ctrl / Shift / Alt that are held now become part of the hotkey (Ctrl + Num 7, ...)
				bindKey(InputConstants.getKey(input), modifiers & 7);
			}
			return true;
		}
		// the same hotkey that opened the settings closes them again
		if (ModKeys.OPEN_SETTINGS != null && ModKeys.matches(ModKeys.OPEN_SETTINGS, keyCode, scanCode)
				&& ModKeys.modsOk(ModKeys.OPEN_SETTINGS, modifiers & 7)) {
			onClose();
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		int button = click.button();   // 1.21.9+: the click comes as one object
		if (rebinding != null) {
			bindKey(InputConstants.Type.MOUSE.getOrCreate(button), ModKeys.heldMods());
			return true;
		}
		return super.mouseClicked(click, doubled);
	}

	// ------------------------------------------------------------------ drawing

	/** super.render() draws the background itself, so the preview and the title go right after it (still under the buttons). */
	@Override
	public void extractBackground(net.minecraft.client.gui.GuiGraphicsExtractor raw, int mouseX, int mouseY, float delta) {
		super.extractBackground(raw, mouseX, mouseY, delta);
		DrawContext ctx = new DrawContext(raw);
		drawPreview(ctx);
		ctx.drawCenteredTextWithShadow(font, title, width / 2, 9, 0xFFFFFF);
	}

	@Override
	public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor raw, int mouseX, int mouseY, float delta) {
		super.extractRenderState(raw, mouseX, mouseY, delta);   // background + preview + title + buttons
		DrawContext ctx = new DrawContext(raw);     // our small helper in front of Minecraft's DrawContext

		if (page == 2) {
			int hy = 50 + ((ModKeys.ALL.size() + 1 + 1) / 2) * 22 + 6;
			for (String k : new String[]{"keys.hint1", "keys.hint2"}) {
				for (FormattedCharSequence line : font.split(tr(k), 310)) {
					ctx.drawCenteredTextWithShadow(font, line, width / 2, hy, 0xAAAAAA);
					hy += 10;
				}
				hy += 4;
			}
		}
	}

	/** Shows where the player will be on the real screen (green = the music window, gray = bar and buttons). */
	private void drawPreview(DrawContext ctx) {
		BrowserPlayer.Layout l = BrowserPlayer.layout(width, height, 1f, false);

		// the music window
		ctx.fill(l.x0, l.winTop, l.x0 + l.w, l.winTop + l.winH, 0x6655FF55);
		ctx.fill(l.x0, l.winTop, l.x0 + l.w, l.winTop + 1, 0xFF55FF55);
		ctx.fill(l.x0, l.winTop + l.winH - 1, l.x0 + l.w, l.winTop + l.winH, 0xFF55FF55);
		ctx.fill(l.x0, l.winTop, l.x0 + 1, l.winTop + l.winH, 0xFF55FF55);
		ctx.fill(l.x0 + l.w - 1, l.winTop, l.x0 + l.w, l.winTop + l.winH, 0xFF55FF55);

		// track name bar (oval), the little buttons (round) + the pin. Without the bar the media buttons move up into its row.
		int gray = 0xCC8A8A8A;
		int r = l.tickerH / 2;
		if (l.tickerW > 0) {
			BrowserPlayer.fillRound(ctx, l.barX, l.tickY, l.tickerW, l.tickerH, r, gray);
		}
		if (l.hasToggle) {
			BrowserPlayer.fillRound(ctx, l.toggleX, l.tickY, l.pinW, l.tickerH, r, gray);
		}
		BrowserPlayer.fillRound(ctx, l.pinX, l.tickY, l.pinW, l.tickerH, r, gray);
		if (l.rszW > 0) {
			BrowserPlayer.fillRound(ctx, l.rszX, l.tickY, l.rszW, l.tickerH, r, gray);
		}

		// media buttons (round)
		for (int cx : l.btnCx) {
			BrowserPlayer.fillRound(ctx, cx - l.btnH / 2, l.btnY, l.btnH, l.btnH, l.btnH / 2, 0xCC6F6F6F);
		}

		// volume slider (rounded)
		if (l.volVisible) {
			BrowserPlayer.fillRound(ctx, l.vx, l.vy, l.vw, l.vh, Math.max(2, l.vw / 3), 0xCC1ED760);
		}
	}

	@Override
	public void removed() {
		PlayerConfig.get().save();
	}

	@Override
	public void onClose() {
		if (minecraft != null) minecraft.gui.setScreen(parent);
	}
}
