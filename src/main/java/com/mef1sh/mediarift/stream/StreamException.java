package com.mef1sh.mediarift.stream;

/** A problem that can be shown to the player as it is (the message is meant to be read). */
public class StreamException extends Exception {
	public StreamException(String message) {
		super(message);
	}
}
