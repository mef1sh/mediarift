package com.mef1sh.mediarift;

import org.joml.Matrix3x2fStack;

/**
 * Since 1.21.6 the menus use a flat (2D) matrix stack. This keeps the old names (push / translate / scale / pop)
 * so the drawing code of the mod did not have to change. The z (depth) part is ignored: menus have no depth any more.
 */
public class MatrixStack {
	private final Matrix3x2fStack m;

	public MatrixStack(Matrix3x2fStack m) {
		this.m = m;
	}

	public void push() {
		m.pushMatrix();
	}

	public void pop() {
		m.popMatrix();
	}

	public void translate(float x, float y, float z) {
		m.translate(x, y);
	}

	public void scale(float x, float y, float z) {
		m.scale(x, y);
	}
}
