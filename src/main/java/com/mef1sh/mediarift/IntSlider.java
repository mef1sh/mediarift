package com.mef1sh.mediarift;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/** A normal Minecraft slider that works with whole numbers, e.g. "Size: 120%". */
public class IntSlider extends AbstractSliderButton {
	private final Component label;
	private final String suffix;
	private final int min;
	private final int max;
	private final IntConsumer onChange;

	public IntSlider(int x, int y, int width, int height, Component label, int min, int max, String suffix,
					 int initial, IntConsumer onChange) {
		super(x, y, width, height, Component.empty(), (double) (initial - min) / (max - min));
		this.label = label;
		this.suffix = suffix;
		this.min = min;
		this.max = max;
		this.onChange = onChange;
		updateMessage();
	}

	public int current() {
		return min + (int) Math.round(value * (max - min));
	}

	@Override
	protected void updateMessage() {
		if (label == null) return; // called by the parent constructor before our fields are set
		setMessage(label.copy().append(Component.literal(": " + current() + suffix)));
	}

	@Override
	protected void applyValue() {
		onChange.accept(current());
	}
}
