package com.mef1sh.mediarift;

import com.mef1sh.mediarift.stream.ToolInstaller;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Component;

/**
 * Loading screen shown once at game start (on the title screen) when streamlink or ffmpeg is missing.
 * Downloads them like the browser library (Chromium) is downloaded, then goes on to the game.
 * The screen stays until the download is finished (no "continue in the background": in the background it could fail
 * and the player would never know). Only when it FAILED there are the buttons "Try again" and "Continue".
 */
public class ToolsDownloadScreen extends Screen {
	private static final int RUNNING = 0, DONE = 1, FAILED = 2;

	private final Screen parent;
	private volatile int state = RUNNING;
	private volatile String status = "";
	private volatile String label = "";
	private volatile long done = 0, total = -1;
	private volatile String error = "";
	private volatile long doneAt = 0;
	/** Which state the buttons were built for (they change when the download ends). */
	private int builtState = -1;
	private Thread worker;

	public ToolsDownloadScreen(Screen parent) {
		super(Component.translatable("mediarift.tools.title"));
		this.parent = parent;
	}

	private static Component tr(String key) {
		return Component.translatable("mediarift.tools." + key);
	}

	@Override
	protected void init() {
		if (worker == null) startWorker();
		builtState = state;
		int cx = width / 2;
		if (state == FAILED) {
			addRenderableWidget(Button.builder(tr("retry"), b -> {
				state = RUNNING;
				error = "";
				label = "";
				done = 0;
				total = -1;
				worker = null;
				rebuildWidgets();
			}).bounds(cx - 102, height - 40, 100, 20).build());
			addRenderableWidget(Button.builder(tr("continue"), b -> onClose()).bounds(cx + 2, height - 40, 100, 20).build());
		}
	}

	/** Esc closes the screen only when the download failed (while it runs there is nothing to skip). */
	@Override
	public boolean shouldCloseOnEsc() {
		return state == FAILED;
	}

	private void startWorker() {
		state = RUNNING;
		status = "";
		Thread t = new Thread(() -> {
			try {
				ToolInstaller.install(LiveTools.toolsDir(), LiveTools.options(), new ToolInstaller.Listener() {
					@Override
					public void status(String text) {
						status = text;
						MediaRiftClient.LOGGER.info("[tools] {}", text);
					}

					@Override
					public void progress(String name, long d, long t) {
						label = name;
						done = d;
						total = t;
					}
				});
				doneAt = System.currentTimeMillis();
				state = DONE;
			} catch (Throwable e) {
				MediaRiftClient.LOGGER.warn("[tools] download failed: {}", e.toString());
				error = e.getMessage() == null ? e.toString() : e.getMessage();
				state = FAILED;
			}
		}, "MediaRift-ToolsDownload");
		t.setDaemon(true);
		worker = t;
		t.start();
	}

	@Override
	public void tick() {
		if (state != builtState) rebuildWidgets();
		if (state == DONE && System.currentTimeMillis() - doneAt > 1500) onClose();
	}

	@Override
	public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor raw, int mouseX, int mouseY, float delta) {
		// super.render() draws the background itself, so it goes FIRST and our text is drawn on top of it
		super.extractRenderState(raw, mouseX, mouseY, delta);
		DrawContext ctx = new DrawContext(raw);   // our small helper in front of Minecraft's DrawContext
		int cx = width / 2;
		int y = height / 2 - 45;

		ctx.drawCenteredTextWithShadow(font, title, cx, y, 0xFFFFFF);
		y += 14;
		for (FormattedCharSequence line : font.split(tr("hint"), 300)) {
			ctx.drawCenteredTextWithShadow(font, line, cx, y, 0xAAAAAA);
			y += 10;
		}
		y += 8;

		// progress bar
		int barW = 220, barH = 10, x0 = cx - barW / 2;
		ctx.fill(x0 - 1, y - 1, x0 + barW + 1, y + barH + 1, 0xFF555555);
		ctx.fill(x0, y, x0 + barW, y + barH, 0xFF111111);
		int s = state;
		if (s == DONE) {
			ctx.fill(x0, y, x0 + barW, y + barH, 0xFF1ED760);
		} else if (s == RUNNING) {
			long d = done, t = total;
			if (t > 0) {
				ctx.fill(x0, y, x0 + (int) (barW * Math.min(1.0, d / (double) t)), y + barH, 0xFF1ED760);
			} else {
				// size unknown: a bar that moves back and forth
				int w = 50;
				int pos = (int) ((System.currentTimeMillis() / 12) % (2 * (barW - w)));
				if (pos > barW - w) pos = 2 * (barW - w) - pos;
				ctx.fill(x0 + pos, y, x0 + pos + w, y + barH, 0xFF1ED760);
			}
		} else {
			ctx.fill(x0, y, x0 + barW, y + barH, 0xFF8A2B2B);
		}
		y += barH + 6;

		// what is happening
		String text;
		int color = 0xFFFFFF;
		if (s == DONE) {
			text = tr("done").getString();
			color = 0x55FF55;
		} else if (s == FAILED) {
			text = error;
			color = 0xFF5555;
		} else {
			long d = done, t = total;
			String st = status;
			if (!label.isEmpty() && d > 0) {
				text = label + ": " + (t > 0 ? (int) (d * 100 / t) + " %  (" + d / 1_000_000 + " / " + t / 1_000_000 + " MB)" : d / 1_000_000 + " MB");
			} else {
				text = st.isEmpty() ? tr("starting").getString() : st;
			}
		}
		for (FormattedCharSequence line : font.split(Component.literal(text), 320)) {
			ctx.drawCenteredTextWithShadow(font, line, cx, y, color);
			y += 10;
		}

		// small warning: an antivirus often blocks the download
		if (s != DONE) {
			y += 10;
			for (FormattedCharSequence line : font.split(tr("antivirus"), 300)) {
				ctx.drawCenteredTextWithShadow(font, line, cx, y, 0xFFAA00);
				y += 10;
			}
		}
	}

	@Override
	public void onClose() {
		if (minecraft != null) minecraft.gui.setScreen(parent);
	}
}
