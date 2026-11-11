package com.mef1sh.mediarift;

import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Component;

/**
 * Minecraft 1.21.6+ changed how the menus are drawn (since 26.1 the "real" one is called GuiGraphicsExtractor: it only collects what to draw, and the game draws
 * it later, all at once). This small class sits in front of the real one and keeps the old way of talking that the
 * rest of the mod was written for (1.20.1): getMatrices().push/translate/scale/pop, draw(), "shader color" alpha, ...
 */
public class DrawContext {
	private final net.minecraft.client.gui.GuiGraphicsExtractor raw;
	private final MatrixStack matrices;
	/** Like the old RenderSystem.setShaderColor(1, 1, 1, alpha): everything drawn is this much see-through. */
	private float alpha = 1f;

	public DrawContext(net.minecraft.client.gui.GuiGraphicsExtractor raw) {
		this.raw = raw;
		this.matrices = new MatrixStack(raw.pose());
	}

	/** The real GuiGraphicsExtractor of Minecraft (for things this class does not have, like drawing the browser picture). */
	public net.minecraft.client.gui.GuiGraphicsExtractor raw() {
		return raw;
	}

	public MatrixStack getMatrices() {
		return matrices;
	}

	/** In 1.20.1 this finished the waiting drawings. Now the game does that itself, so nothing to do. */
	public void draw() {
	}

	/** Replaces RenderSystem.setShaderColor(1, 1, 1, a). */
	public void setAlpha(float a) {
		this.alpha = Math.max(0f, Math.min(1f, a));
	}

	// ---------------------------------------------------------------- colors

	/** Fills: the color as it is, only the "shader" alpha on top. */
	private int fillColor(int argb) {
		if (alpha >= 1f) return argb;
		int a = Math.round(((argb >>> 24) & 0xFF) * alpha);
		return (a << 24) | (argb & 0xFFFFFF);
	}

	/** Component: like old Minecraft, a color without alpha (0xFFFFFF) means "fully visible". Since 1.21.6 it would be invisible. */
	private int textColor(int argb) {
		if ((argb & 0xFC000000) == 0) argb |= 0xFF000000;
		return fillColor(argb);
	}

	public void fill(int x1, int y1, int x2, int y2, int color) {
		raw.fill(x1, y1, x2, y2, fillColor(color));
	}

	// ---------------------------------------------------------------- text

	public void drawText(Font tr, String text, int x, int y, int color, boolean shadow) {
		raw.text(tr, text, x, y, textColor(color), shadow);
	}

	public void drawText(Font tr, Component text, int x, int y, int color, boolean shadow) {
		raw.text(tr, text, x, y, textColor(color), shadow);
	}

	public void drawText(Font tr, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
		raw.text(tr, text, x, y, textColor(color), shadow);
	}

	public void drawTextWithShadow(Font tr, String text, int x, int y, int color) {
		drawText(tr, text, x, y, color, true);
	}

	public void drawTextWithShadow(Font tr, Component text, int x, int y, int color) {
		drawText(tr, text, x, y, color, true);
	}

	public void drawTextWithShadow(Font tr, FormattedCharSequence text, int x, int y, int color) {
		drawText(tr, text, x, y, color, true);
	}

	public void drawCenteredTextWithShadow(Font tr, String text, int cx, int y, int color) {
		drawText(tr, text, cx - tr.width(text) / 2, y, color, true);
	}

	public void drawCenteredTextWithShadow(Font tr, Component text, int cx, int y, int color) {
		drawText(tr, text, cx - tr.width(text) / 2, y, color, true);
	}

	public void drawCenteredTextWithShadow(Font tr, FormattedCharSequence text, int cx, int y, int color) {
		drawText(tr, text, cx - tr.width(text) / 2, y, color, true);
	}

	// ---------------------------------------------------------------- scissor (only this rectangle is drawn)

	/**
	 * Screen coordinates, NOT moved by translate() (like in 1.20.1). Since 1.21.6 the real one is moved by the current
	 * translate, so for a moment we draw with "no move" while the rectangle is set.
	 */
	public void enableScissor(int x1, int y1, int x2, int y2) {
		org.joml.Matrix3x2fStack m = raw.pose();
		m.pushMatrix();
		m.identity();
		raw.enableScissor(x1, y1, x2, y2);
		m.popMatrix();
	}

	public void disableScissor() {
		raw.disableScissor();
	}
}
