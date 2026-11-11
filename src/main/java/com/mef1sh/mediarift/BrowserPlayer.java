package com.mef1sh.mediarift;

import de.keksuccino.rinku.Rinku;
import de.keksuccino.rinku.RinkuBrowser;
import com.mef1sh.mediarift.stream.LiveStreamService;
import com.mef1sh.mediarift.stream.StreamException;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Component;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.CefSettings;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;

import java.util.List;

/**
 * The music player. Top to bottom (when it is in the upper half of the screen):
 *   [ music website (slides down) ]  [ scrolling track name | pin ]  [ prev | play/pause | next ]
 * In the lower half of the screen the website slides UP instead, so it never goes off screen.
 * Size, place, buttons, pin and volume are set in Mod Menu (see MusicConfigScreen).
 */
public class BrowserPlayer {
	// ====== things you can tweak ======
	/** true = text moves to the right, false = to the left. */
	private static final boolean SCROLL_RIGHT = true;
	/** Size of the music window in REAL screen pixels at 100% size. */
	static final int REAL_W = 440;
	static final int REAL_H = 460;
	/** Page that opens first (change it in game with /sb ytmusic, /sb youtube, /sb url ...). */
	private static final String DEFAULT_HOME = "https://music.youtube.com";
	// ==================================

	static final int MARGIN = 4;
	static final int TICKER_H = 14;
	static final int BTN_H = 18;
	static final int GAP = 3;
	/** Smallest size of the window when you drag the resize button (in REAL screen pixels). */
	private static final int MIN_REAL_W = 240, MIN_REAL_H = 200;

	// colours of the round parts (0xAARRGGBB): the song bar is a bit lighter, the round buttons a bit darker than the bar
	private static final int BAR_FILL = 0xFF232323;
	private static final int BTN_FILL = 0xFF151515;
	private static final int BTN_HOVER = 0xFF2E2E2E;
	private static final int RIM = 0xFF3A3A3A;
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	// volume slider: the track has its own (slightly bluish) dark shade, so it does not look the same as the song bar
	private static final int VOL_TRACK = 0xFF1A1E25;
	private static final int VOL_RIM = 0xFF3B414B;
	private static final int VOL_GREEN = 0xFF1ED760;
	private static final int VOL_GREEN_HOVER = 0xFF3DE07A;
	private static final int VOL_KNOB = 0xFFDADDE2;
	private static final int VOL_KNOB_HOVER = 0xFFFFFFFF;

	private static final String[] ACTION_NAMES = {"prev", "play", "next"};

	/** Asks the page what is playing and prints it to the browser console (we read it in Java). */
	private static final String POLL_JS = """
			(function(){try{
			 var ms=navigator.mediaSession;var st=ms?ms.playbackState:'none';var md=ms?ms.metadata:null;var t='';
			 if(md){t=(md.artist?md.artist+' - ':'')+(md.title||'');}
			 var m=document.querySelector('video,audio');
			 if((st==='none'||!st)&&m){st=m.paused?'paused':'playing';}
			 if(!t&&st==='playing'){t=document.title;}
			 console.log('MRMOD|'+st+'|'+location.hostname+'|'+t);
			 var live='0',h=location.hostname,pth=location.pathname;
			 if(h.indexOf('youtube.com')>=0&&h.indexOf('music.')!==0&&(pth.indexOf('/watch')===0||pth.indexOf('/live/')===0)){
			  var pl=document.getElementById('movie_player');
			  if(pl&&pl.getVideoData){var vd=pl.getVideoData();if(vd&&vd.isLive)live='1';}
			 }else if(h==='kick.com'||h.endsWith('.kick.com')){
			  var ks=pth.split('/').filter(function(x){return x;});
			  var kskip=['categories','category','browse','following','dashboard','settings','search','clips','videos','stream','subscriptions','about','support','terms-of-service','privacy-policy','community-guidelines','dmca'];
			  if(ks.length===1&&kskip.indexOf(ks[0].toLowerCase())<0)live='1';
			 }else if(h==='twitch.tv'||h.endsWith('.twitch.tv')){
			  var seg=pth.split('/').filter(function(x){return x;});
			  var skip=['directory','downloads','jobs','turbo','settings','subscriptions','wallet','inventory','drops','friends','messages','search','prime','p','moderator','popout','videos','clips','store'];
			  if(seg.length===1&&skip.indexOf(seg[0].toLowerCase())<0)live='1';
			 }
			 console.log('MRURL|'+live+'|'+location.href);
			}catch(e){}})();
			""";

	/** Clicks the prev / play / next button of the current site (ACTION = prev, play or next). */
	private static final String CONTROL_JS = """
			(function(action){
			 var h=location.hostname,s;
			 if(h.indexOf('music.youtube')>=0){s={prev:['.previous-button','ytmusic-player-bar .previous-button'],play:['#play-pause-button','.play-pause-button'],next:['.next-button','ytmusic-player-bar .next-button']};}
			 else if(h.indexOf('youtube')>=0){s={prev:['.ytp-prev-button'],play:['.ytp-play-button'],next:['.ytp-next-button']};}
			 else{s={prev:[],play:[],next:[]};}
			 var l=s[action]||[];
			 for(var i=0;i<l.length;i++){var e=document.querySelector(l[i]);if(e){e.click();return;}}
			 if(action==='play'){var m=document.querySelector('video,audio');if(m){if(m.paused){m.play();}else{m.pause();}}}
			})('ACTION');
			""";

	private static String controlJs(String action) {
		return CONTROL_JS.replace("ACTION", action);
	}

	/** Sets the volume (0..100) of every <video>/<audio> on the page. */
	private static String volumeJs(int percent) {
		// with the page helper installed the volume of the mod always wins (the site cannot make a song loud when it changes the song);
		// without it: set the volume of every <video> / <audio> directly
		return "(function(v){try{if(window.__mr&&window.__mr.setVol){window.__mr.setVol(v);return;}"
				+ "document.querySelectorAll('video,audio').forEach(function(m){m.volume=v;});}catch(e){}})("
				+ (percent / 100.0) + ");";
	}

	private static final java.nio.file.Path HOME_FILE =
			net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("mediarift_home.txt");

	/** The page that opens first. Remembered between launches. */
	public static String homeUrl() {
		try {
			if (java.nio.file.Files.exists(HOME_FILE)) {
				String u = java.nio.file.Files.readString(HOME_FILE).trim();
				if (u.startsWith("http")) return u;
			}
		} catch (java.io.IOException ignored) {
		}
		return DEFAULT_HOME;
	}

	private static void rememberHome(String url) {
		try {
			java.nio.file.Files.writeString(HOME_FILE, url);
		} catch (java.io.IOException ignored) {
		}
	}

	private static final BrowserPlayer INSTANCE = new BrowserPlayer();

	public static BrowserPlayer get() {
		return INSTANCE;
	}

	private RinkuBrowser browser;
	/** The last real web page (not our local stream page) the browser told us about. */
	private volatile String currentHref = "";
	/** The page address we already switched to the live stream converter on. It stays set while we are on it, so "back" does not jump into it again. */
	private volatile String autoStartedHref = "";
	private long lastShimMs = 0;
	private volatile String nowPlaying = "";
	private volatile boolean playing = false;
	/** Name of the site that is open now (for example "kick.com"), told by the page itself. */
	private volatile String siteHost = "";

	private boolean open = false;
	private float anim = 0f;
	private long lastNanos = 0;
	private long lastPollMs = 0;
	private long lastVolMs = 0;
	/** After a click we ask the page what is playing much more often, so the icons change at once. */
	private long fastPollUntil = 0;
	private boolean draggingVolume = false;
	private double lastZoomLevel = Double.NaN;

	// ---- dragging the resize button (like pulling the corner of a Windows window)
	private boolean resizing = false;
	private int rsMx0, rsMy0, rsX0, rsW0, rsH0, rsTop0, rsTickY0, rsStackH;
	private boolean rsGrabRight, rsUp;
	private double rsScale = 2.0;
	private long lastResizeMs = 0;

	// short on-screen hints when the volume / pin is changed with a hotkey
	private static final long FLASH_IN = 160, FLASH_HOLD = 1300, FLASH_OUT = 400;
	private long volFlashShown = 0, volFlashLast = 0;
	private long pinFlashShown = 0, pinFlashLast = 0;
	/** How long the fade-out of each hint takes (ms). Normally FLASH_OUT, but only CHAT_FADE when the chat opens. */
	private long volFlashOut = FLASH_OUT, pinFlashOut = FLASH_OUT;
	/** When the chat opens, a hint that is still showing fades out this fast (ms), so it is gone as the real parts slide in. */
	private static final long CHAT_FADE = 100;
	private boolean focused = false;      // keyboard goes to the browser
	private boolean pressed = false;      // mouse button is held inside the browser
	private boolean hudMode = false;      // drawing while the chat is closed
	private int lastSentX = -1, lastSentY = -1;
	private int lastBw = 0, lastBh = 0;

	// ---- sliding of the parts around the window (pin, arrow button, gray bar, buttons, volume slider)
	/** How long (seconds) the parts take to slide in / out. They move by time, so they land exactly on their place. */
	private static final float SLIDE_TIME = 0.26f;
	/** 0 = the parts that belong to the chat are hidden, 1 = fully slid in. */
	private float chrome = 0f;
	/** Same for the parts that also stay with the chat closed (only when pinned): they slide away when you unpin. -1 = not set yet. */
	private float keepVis = -1f;
	/** true = the parts slide in / out from behind the window, false = from the edge of the screen. */
	private boolean behind = false;
	private boolean wasKeep = false;
	private int curSW = 0, curSH = 0;
	private int slideDx = 0, slideDy = 0;
	private boolean slideScissor = false;

	/** Layout of the last drawn frame. */
	private Layout L;

	public void reset() {
		// a pinned player keeps its window open between chats; a normal one starts closed every time
		boolean keep = PlayerConfig.get().pinned || PlayerConfig.get().superPinned;
		if (!keep) {
			open = false;
			anim = 0f;
		}
		// the short hotkey hints (volume slider, pin) that are still showing do not vanish: they fade out smoothly
		// while the real parts slide in
		long nowMs = System.currentTimeMillis();
		if (flashFactor(volFlashShown, volFlashLast, nowMs, volFlashOut) > 0f) {
			volFlashLast = fastFade(volFlashLast, volFlashOut, nowMs);
			volFlashOut = CHAT_FADE;
		}
		if (flashFactor(pinFlashShown, pinFlashLast, nowMs, pinFlashOut) > 0f) {
			pinFlashLast = fastFade(pinFlashLast, pinFlashOut, nowMs);
			pinFlashOut = CHAT_FADE;
		}
		behind = computeBehind(keep);   // chat opens: slide out from behind the window (if it is on screen) or from the screen edge
		lastNanos = 0;
		setFocused(false);
		pressed = false;
		draggingVolume = false;
		resizing = false;
	}

	public void onChatClosed() {
		setFocused(false);
		pressed = false;
		behind = computeBehind(PlayerConfig.get().pinned || PlayerConfig.get().superPinned);
		// the short hotkey hints (volume slider, pin) are over: otherwise they stay on screen under the real parts
		// that slide away now, and disappear one after another
		volFlashShown = 0;
		volFlashLast = 0;
		pinFlashShown = 0;
		pinFlashLast = 0;
		if (draggingVolume) {
			draggingVolume = false;
			PlayerConfig.get().save();
		}
		if (resizing) {
			resizing = false;
			PlayerConfig.get().save();
		}
	}

	/** Stops the music (used when you leave the world). */
	public void pauseAll() {
		playing = false;
		runJs("(function(){try{document.querySelectorAll('video,audio').forEach(function(m){m.pause();});}catch(e){}})();");
	}

	private void setFocused(boolean f) {
		focused = f;
		if (browser != null) browser.setFocus(f);
	}

	// ------------------------------------------------------------ slide animation

	/** Moves cur towards target by at most step. */
	private static float approach(float cur, float target, float step) {
		if (cur < target) return Math.min(target, cur + step);
		return Math.max(target, cur - step);
	}

	/** Smooth start and smooth end: 0 stays 0, 1 stays 1. */
	private static float smooth(float t) {
		t = Math.max(0f, Math.min(1f, t));
		return t * t * (3f - 2f * t);
	}

	/** true while something is still sliding / folding, so it must be drawn even though the player is not pinned. */
	public boolean isBusy() {
		PlayerConfig c = PlayerConfig.get();
		boolean keep = c.pinned || c.superPinned;
		return anim > 0.001f || chrome > 0.001f || (!keep && keepVis > 0.001f);
	}

	/** Is there something on screen the parts can hide behind (the window, or the gray bar of a pinned player)? */
	private boolean computeBehind(boolean keep) {
		PlayerConfig c = PlayerConfig.get();
		return anim > 0.05f || (keep && c.showTicker && !c.hideTickerWhenChatClosed);
	}

	/** How far (in pixels) the parts must move to be completely behind the screen edge. */
	private float edgeShift(Layout l) {
		boolean fromLeft = PlayerConfig.get().posX < 50;
		int left = l.x0, right = l.x0 + l.w;
		if (!PlayerConfig.get().showTicker) {
			// no song bar: only the little buttons and the media buttons have to leave the screen
			int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
			if (l.hasToggle) {
				lo = Math.min(l.toggleX, Math.min(l.pinX, l.rszX));
				hi = Math.max(l.toggleX + l.pinW, Math.max(l.pinX + l.pinW, l.rszX + l.rszW));
			}
			for (int cx : l.btnCx) {
				lo = Math.min(lo, cx - l.btnH / 2);
				hi = Math.max(hi, cx + l.btnH / 2 + 1);
			}
			if (lo <= hi) {
				left = lo;
				right = hi;
			}
		}
		if (l.volVisible) {
			left = Math.min(left, l.vx);
			right = Math.max(right, l.vx + l.vw);
		}
		return fromLeft ? -(right + MARGIN) : (curSW - left + MARGIN);
	}

	/**
	 * Starts drawing one part that slides. vis: 0 = out of sight, 1 = in place.
	 * side = true for the volume slider (slides sideways out of the window), false for the parts under the window.
	 * top / height = place of the part (used to hide it behind the line it comes out of).
	 * @return false = nothing to draw (then do NOT call endSlide).
	 */
	private boolean beginSlide(DrawContext ctx, Layout l, float vis, boolean side, int top, int height) {
		slideScissor = false;
		slideDx = 0;
		slideDy = 0;
		if (vis <= 0.001f) return false;
		MatrixStack m = ctx.getMatrices();
		m.push();
		float out = 1f - vis;
		if (out > 0.00001f) {
			// the move is NOT rounded to whole pixels (only the clip area is): a rounded move makes the last bit
			// of the slide jump from pixel to pixel instead of gliding
			if (!behind) {
				float fdx = edgeShift(l) * out;
				slideDx = Math.round(fdx);
				m.translate(fdx, 0f, 0f);
			} else if (!side) {
				float fdy = -(height + 1) * out;
				slideDy = Math.round(fdy);
				m.translate(0f, fdy, 0f);
				ctx.enableScissor(0, Math.max(0, Math.min(top, curSH - 1)), curSW, curSH);
				slideScissor = true;
			} else {
				float dist = (l.vw + l.gap) * out;
				if (l.volRight) {
					slideDx = -Math.round(dist);
					m.translate(-dist, 0f, 0f);
					ctx.enableScissor(Math.min(l.x0 + l.w, curSW - 1), 0, curSW, curSH);
				} else {
					slideDx = Math.round(dist);
					m.translate(dist, 0f, 0f);
					ctx.enableScissor(0, 0, Math.max(l.x0, 1), curSH);
				}
				slideScissor = true;
			}
		}
		return true;
	}

	private void endSlide(DrawContext ctx) {
		if (slideScissor) {
			ctx.disableScissor();
			slideScissor = false;
		}
		ctx.getMatrices().pop();
		slideDx = 0;
		slideDy = 0;
	}

	// ---------------------------------------------------------------- geometry

	/** Where everything is. Also used by the settings screen for the preview. */
	static final class Layout {
		int x0, w, winFull, winH, winTop, texTop;
		int tickY, tickerW, pinW, tickerH, rowH;
		int pinX, toggleX, rszX, rszW, rszGap, barX;
		boolean textBar, hasToggle;
		/** true = the player is on the right side: the pin and the resize button sit at the LEFT end of the window. */
		boolean mirror;
		/** true = the resize button pulls the RIGHT side of the window, false = the left side. */
		boolean grabRight;
		int btnY, btnH, gap, btnCount;
		/** Centre (x) of every round media button (prev / play / next). */
		int[] btnCx = new int[0];
		int top, bottom;
		boolean volVisible, volRight;
		int vx, vy, vw, vh, fpinX;
		float s;
		double scale;
	}

	/**
	 * The player is sized relative to the game window (1920 x 1050 = 100 %): when you make the game window smaller the
	 * whole player gets smaller too, instead of staying the same size in pixels and looking "fat".
	 */
	static float fitFactor() {
		com.mojang.blaze3d.platform.Window w = Minecraft.getInstance().getWindow();
		float f = Math.min(w.getWidth() / 1920f, w.getHeight() / 1050f);
		return Math.max(0.4f, Math.min(2f, f));
	}

	/**
	 * @param anim 0 = window closed, 1 = window fully open
	 * @param hud  true when the chat is closed (pinned mode): no pin button, buttons may be hidden
	 */
	static Layout layout(int screenW, int screenH, float anim, boolean hud) {
		PlayerConfig c = PlayerConfig.get();
		Layout l = new Layout();
		l.scale = Minecraft.getInstance().getWindow().getGuiScale();
		// k = size in real screen pixels, relative to the size of the game window (a small game window = a small player,
		// so the player does not get "fat"); l.s = the same for things that are drawn in GUI pixels
		float k = c.sizePercent / 100f * fitFactor();
		l.s = k * 2f / (float) l.scale;
		l.w = Math.max(80, Math.round(REAL_W * k * c.widthPercent / 100f / (float) l.scale));
		l.winFull = Math.max(40, Math.round(REAL_H * k * c.heightPercent / 100f / (float) l.scale));
		l.tickerH = Math.max(9, Math.round(TICKER_H * l.s));
		l.btnH = Math.max(10, Math.round(BTN_H * l.s));
		l.gap = Math.max(2, Math.round(GAP * l.s));
		l.btnCount = (hud && c.hideButtonsWhenChatClosed) ? 0 : c.visibleActions().length;
		// without the song bar the media buttons stand in the SAME row as the little buttons: all are round buttons of one size
		if (!c.showTicker) {
			l.tickerH = Math.max(l.tickerH, l.btnH);
			l.btnH = l.tickerH;
		}
		l.winH = Math.round(l.winFull * anim);
		// the row under the window: [gray text bar][pin]  (without the text bar: [open/close][pin])
		l.textBar = c.showTicker && !(hud && c.hideTickerWhenChatClosed);
		l.rowH = (!hud || l.textBar || (!c.showTicker && l.btnCount > 0)) ? l.tickerH : 0;
		l.pinW = hud ? 0 : l.tickerH;
		l.hasToggle = !hud && !c.showTicker;
		boolean fromLeft = c.posX < 50;
		// player on the right: the pin and the resize button sit at the LEFT end of the window (a mirror image of the left
		// side), so the window can be pulled bigger to the left, and without the song bar the little buttons fly diagonally
		// to their screen corner when the window closes
		l.mirror = !hud && !fromLeft;
		l.grabRight = !l.mirror;
		// the resize button grows out while the window opens (it is not there while the window is closed / in pinned mode)
		float grow = hud ? 0f : smooth(anim);
		l.rszW = Math.round(l.tickerH * grow);
		l.rszGap = Math.round(l.gap * grow);
		if (hud) l.tickerW = l.w;
		else if (c.showTicker) l.tickerW = l.w - l.pinW - l.gap - l.rszW - l.rszGap;
		else l.tickerW = 0;

		int btnRow = (c.showTicker && l.btnCount > 0) ? l.gap + l.btnH : 0;   // own row only with the song bar
		int stackH = l.rowH + btnRow;

		l.x0 = MARGIN + Math.round(Math.max(0, screenW - l.w - 2 * MARGIN) * c.posX / 100f);

		int free = Math.max(0, screenH - stackH - 2 * MARGIN);
		int ys = MARGIN + Math.round(free * c.posY / 100f);   // top of the "ticker + buttons" block when closed
		boolean down = c.posY < 50;                            // upper half: window slides down, lower half: up
		l.winTop = down ? ys : ys - l.winH;
		l.tickY = down ? ys + l.winH : ys;
		l.texTop = down ? l.winTop + l.winH - l.winFull : l.winTop;

		// keep everything inside the screen
		int shift = 0;
		int bottom = l.tickY + stackH;
		if (bottom > screenH - MARGIN) shift = screenH - MARGIN - bottom;
		int topEdge = (l.winH > 0 ? l.winTop : l.tickY) + shift;
		if (topEdge < MARGIN) shift += MARGIN - topEdge;
		l.winTop += shift;
		l.texTop += shift;
		l.tickY += shift;

		l.btnY = c.showTicker ? l.tickY + l.rowH + l.gap : l.tickY;
		l.top = l.winH > 0 ? l.winTop : l.tickY;
		l.bottom = l.tickY + stackH;
		if (l.mirror) {
			l.pinX = l.x0;
			l.rszX = l.pinX + l.pinW + l.rszGap;
			l.toggleX = l.rszX + l.rszW + l.gap;
			// with the song bar the bar takes the place after the buttons (where the arrow button would be)
			l.barX = c.showTicker ? l.toggleX : l.x0;
		} else {
			l.pinX = l.x0 + l.w - l.pinW;
			l.rszX = l.pinX - l.rszGap - l.rszW;
			l.toggleX = l.rszX - l.gap - l.pinW;
			l.barX = l.x0;
		}

		// vertical volume slider: on the right of the player, or on the left if there is no room
		l.volVisible = c.showVolumeBar && !(hud && c.hideVolumeBarWhenChatClosed);
		l.vw = Math.max(8, Math.round(10 * l.s));
		boolean right = l.x0 + l.w + l.gap + l.vw <= screenW - MARGIN;
		l.vx = right ? l.x0 + l.w + l.gap : l.x0 - l.gap - l.vw;
		// a fixed size, and a place that depends ONLY on the "up-down" setting, so the slider never
		// jumps when you open the chat, hide buttons, etc.  (0 % = top edge, 100 % = bottom edge)
		l.vh = Math.max(40, Math.round(100 * l.s));
		l.vy = MARGIN + Math.round(Math.max(0, screenH - l.vh - 2 * MARGIN) * c.posY / 100f);
		l.volRight = right;
		l.fpinX = fromLeft ? l.x0 + l.w - l.tickerH : l.x0;   // where the pin button is (or would be) in the chat

		// centres of the media buttons. With the song bar they stand close together in their own row under the bar: at the
		// LEFT end of the window, or at the RIGHT end when the player is on the right side. The width of the window does
		// not change this (no gaps that grow when the window gets bigger).
		int n = Math.min(l.btnCount, c.visibleActions().length);
		l.btnCx = new int[n];
		if (c.showTicker) {
			int groupW = n * l.btnH + Math.max(0, n - 1) * l.gap;
			int start = fromLeft ? l.x0 : l.x0 + l.w - groupW;
			for (int i = 0; i < n; i++) l.btnCx[i] = start + i * (l.btnH + l.gap) + l.btnH / 2;
		}

		// no song bar: media buttons + little buttons + volume slider all stand in one row. While the window is open the
		// media buttons are on the free side (where the bar would be) and the little buttons on the other side. While the
		// window is closed everything moves to the corner of the screen (the side of the "left-right" place) and stays
		// in the same order: on the left [media][arrow][pin][volume], on the right [volume][pin][arrow][media].
		if (!c.showTicker) {
			float closed = 1f - Math.max(0f, Math.min(1f, anim));
			int ps = l.tickerH;
			int groupW = n * ps + Math.max(0, n - 1) * l.gap;
			int afterGap = n > 0 ? l.gap : 0;
			int openStart = fromLeft ? l.x0 : l.x0 + l.w - groupW;
			int restStart, cPin, cToggle, cVol;
			if (fromLeft) {
				restStart = MARGIN;
				cToggle = restStart + groupW + afterGap;
				cPin = cToggle + ps + l.gap;
				cVol = cPin + ps + l.gap;
			} else {
				restStart = screenW - MARGIN - groupW;
				cToggle = restStart - afterGap - ps;
				cPin = cToggle - l.gap - ps;
				cVol = cPin - l.gap - l.vw;
			}
			int start = Math.round(openStart + (restStart - openStart) * closed);
			for (int i = 0; i < n; i++) l.btnCx[i] = start + i * (ps + l.gap) + ps / 2;
			l.pinX = Math.round(l.pinX + (cPin - l.pinX) * closed);
			l.toggleX = Math.round(l.toggleX + (cToggle - l.toggleX) * closed);
			l.rszX = Math.round(l.rszX + (cToggle - l.rszX) * closed);
			l.vx = Math.round(l.vx + (cVol - l.vx) * closed);
			l.fpinX = Math.round(l.fpinX + (cPin - l.fpinX) * closed);
			if (closed > 0.5f) l.volRight = fromLeft;
		}
		return l;
	}

	private static boolean inside(double mx, double my, int x, int y, int ww, int hh) {
		return mx >= x && mx < x + ww && my >= y && my < y + hh;
	}

	private boolean insideBrowser(double mx, double my) {
		Layout l = L;
		return l != null && anim > 0.95f && inside(mx, my, l.x0 + 1, l.texTop + 1, l.w - 2, l.winFull - 2);
	}

	private int bx(double mx) {
		return (int) ((mx - (L.x0 + 1)) * L.scale);
	}

	private int by(double my) {
		return (int) ((my - (L.texTop + 1)) * L.scale);
	}

	// ----------------------------------------------------------------- browser

	private void ensureBrowser() {
		if (browser != null || !Rinku.isInitialized()) return;
		Minecraft mc = Minecraft.getInstance();
		Layout t = layout(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), 0f, false);
		int bw = (int) ((t.w - 2) * t.scale), bh = (int) ((t.winFull - 2) * t.scale);
		browser = Rinku.createBrowser(homeUrl(), false, bw, bh);
		lastBw = bw;
		lastBh = bh;

		Rinku.getClient().addDisplayHandler(new CefDisplayHandlerAdapter() {
			@Override
			public boolean onConsoleMessage(CefBrowser b, CefSettings.LogSeverity level, String message, String source, int line) {
				if (message != null && message.startsWith("MRURL|")) {
					String[] parts = message.split("\\|", 3);
					if (parts.length == 3) onUrlReport("1".equals(parts[1]), parts[2]);
					return true;
				}
				if (message != null && message.startsWith("MRDBG|")) {
					LiveTools.chat(message.substring("MRDBG|".length()));   // the answer of /sb debug
					return true;
				}
				if (message != null && message.startsWith("MRMOD|")) {
					String[] parts = message.split("\\|", 4);
					if (parts.length == 4) {
						playing = "playing".equals(parts[1]);
						siteHost = parts[2];
						nowPlaying = parts[3];
					}
					return true;
				}
				return false;
			}
		});
		Rinku.getClient().addLoadHandler(new CefLoadHandlerAdapter() {
			@Override
			public void onLoadEnd(CefBrowser b, CefFrame frame, int httpStatusCode) {
				if (b == browser) {
					lastZoomLevel = zoomLevel();
					b.setZoomLevel(lastZoomLevel);
					applyVolume();
					injectShim();
					applyResolution();
				}
			}
		});
	}

	/** The page zoom to use now (from the settings). */
	private double zoomLevel() {
		return PlayerConfig.get().zoomTenths / 10.0;
	}

	private void runJs(String js) {
		if (browser != null) browser.executeJavaScript(js, "", 0);
	}

	/** Logs out of every site: deletes all cookies of the built-in browser, then reloads the page. */
	public String clearLogins() {
		try {
			org.cef.network.CefCookieManager.getGlobalManager().deleteCookies("", "");
		} catch (Throwable t) {
			return "Could not clear the logins: " + t;
		}
		runJs("try{localStorage.clear();sessionStorage.clear();}catch(e){}");
		if (browser != null) browser.reload();
		return "Logins cleared (all sites). Open the site again and sign in.";
	}

	/** Puts the volume from the settings onto the page. */
	public void applyVolume() {
		runJs(volumeJs(PlayerConfig.get().volume));
	}

	/** Tells the page (YouTube) which resolution the settings ask for. */
	public void applyResolution() {
		runJs("try{if(window.__mr&&window.__mr.setRes)window.__mr.setRes(" + PlayerConfig.get().resolution + ");}catch(e){}");
	}

	// -------------------------------------------------------------- live streams / videos the browser cannot play

	/** The page helper script (page_shim.js): volume guard, answers /psb debug. */
	private static String shimTemplate;

	private static synchronized String shimJs() {
		if (shimTemplate == null) {
			try (java.io.InputStream in = BrowserPlayer.class.getResourceAsStream("/assets/mediarift/page_shim.js")) {
				shimTemplate = in == null ? "" : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			} catch (java.io.IOException e) {
				shimTemplate = "";
			}
		}
		return shimTemplate;
	}

	private void injectShim() {
		String js = shimJs();
		if (!js.isEmpty()) runJs(js);
	}

	/**
	 * The page told us its address (about once a second). A live stream (YouTube live, a Twitch channel) cannot be played by this
	 * browser (H.264 / AAC), so it is handed to streamlink + ffmpeg and the player opens the converted stream instead.
	 */
	private void onUrlReport(boolean live, String href) {
		if (href == null || href.isEmpty()) return;
		if (LiveStreamService.get().isLocalUrl(href)) return;   // our own converted stream page
		currentHref = href;
		PlayerConfig cfg = PlayerConfig.get();
		if (!href.equals(autoStartedHref)) autoStartedHref = "";   // another page: the old one may start again next time
		if (!live || !cfg.liveEnabled || !cfg.liveAuto || href.equals(autoStartedHref)) return;
		autoStartedHref = href;
		final String url = href;
		Minecraft.getInstance().execute(() -> LiveTools.chat(startLive(url, looksLikeVod(url), true)));
	}

	/** A video / clip / VOD page (it has an end), not a live channel. */
	private static boolean looksLikeVod(String href) {
		String h = href.toLowerCase(java.util.Locale.ROOT);
		return h.contains("/videos/") || h.contains("/video/") || h.contains("/clip") || h.contains("vimeo.com/") || h.contains("dailymotion.com/");
	}

	/** The last normal page. */
	public String currentPageUrl() {
		return currentHref;
	}

	/** /sb live: opens a stream (or video) of the page through streamlink + ffmpeg, as a new step in the history. */
	public String startLiveManual(String pageUrl) {
		return startLive(pageUrl, looksLikeVod(pageUrl), false);
	}

	/** Opens a stream (YouTube live, Twitch, Kick ...) through streamlink + ffmpeg. @return a message for the player. */
	private String startLive(String pageUrl, boolean vod, boolean replace) {
		PlayerConfig cfg = PlayerConfig.get();
		if (!cfg.liveEnabled) return "Live streams are switched off (liveEnabled in config/mediarift.json).";
		if (!Rinku.isInitialized()) return "The browser (Rinku) is not ready yet.";
		String title = nowPlaying == null || nowPlaying.isBlank() ? siteHost : nowPlaying;
		try {
			String local = LiveStreamService.get().prepare(pageUrl, title, vod, LiveTools.options(), LiveTools.toolsDir());
			autoStartedHref = pageUrl;
			showLocalPage(local, replace);
			return "Converting H.264 to " + ("vp8".equalsIgnoreCase(cfg.liveCodec) ? "VP8" : "VP9") + " (the picture appears in a few seconds)...";
		} catch (StreamException e) {
			return e.getMessage() + LiveTools.autoInstallIfMissing();
		}
	}

	/**
	 * Shows our converted picture IN PLACE of the page that is open now. The history gets no extra step: "back" goes to the page
	 * you came from (not to the page that cannot play the video), and "forward" brings the picture back.
	 */
	private void showLocalPage(String local, boolean replace) {
		open = true;
		ensureBrowser();
		if (browser == null) return;
		String cur = currentHref;
		if (replace && cur != null && !cur.isEmpty()) {
			runJs("location.replace('" + local.replace("'", "%27") + "');");
		} else {
			browser.loadURL(local);
		}
	}

	/** /sb live stop: stops the converter and goes back to the page the stream came from. */
	public String stopLive() {
		LiveStreamService.get().stopPipeline();
		if (browser != null && browser.canGoBack()) browser.goBack();
		return "Stream stopped.";
	}

	/** /psb debug: the page says what this browser REALLY can play (the answer comes to the chat). */
	public void debugCodecs() {
		if (browser == null) {
			LiveTools.chat("The player window is not open yet: open it first (chat, click the gray bar), go to the site, then type /psb debug.");
			return;
		}
		injectShim();
		runJs("try{if(window.__mr&&window.__mr.debug)window.__mr.debug();}catch(e){console.log('MRDBG|debug failed: '+e);}");
	}

	/** true while the browser has the keyboard (the player window was clicked). */
	public boolean isFocused() {
		return focused;
	}

	// ------------------------------------------------------------------ render

	/**
	 * How far "up" (towards you) the player is drawn, in GUI depth units. The player is drawn ABOVE everything else of the screen,
	 * also above the TEXT of the buttons (text is drawn in its own batch, so the draw order alone is not enough). 0 = normal order
	 * (used when the window must be BEHIND the menu buttons). Tooltips of the game use 400, so this is higher.
	 */
	public static final float LIFT = 500f;
	private float lift = LIFT;

	public void setLift(float z) {
		lift = z;
	}

	/** Drawn on top of the open chat. */
	public void render(DrawContext ctx, int mouseX, int mouseY, int screenW, int screenH) {
		render(ctx, mouseX, mouseY, screenW, screenH, false);
	}

	/** Drawn while the chat is closed (only when the player is pinned). */
	public void renderHud(DrawContext ctx, int screenW, int screenH) {
		render(ctx, -10000, -10000, screenW, screenH, true);
	}

	private void render(DrawContext ctx, int mouseX, int mouseY, int screenW, int screenH, boolean hud) {
		// "in front": everything of the player goes into a new layer ABOVE everything the screen drew (also above button texts)
		if (lift > 0f) ctx.raw().nextStratum();
		MatrixStack lm = ctx.getMatrices();
		lm.push();
		lm.translate(0f, 0f, lift);
		try {
			renderInner(ctx, mouseX, mouseY, screenW, screenH, hud);
		} finally {
			ctx.draw();
			lm.pop();
		}
	}

	private void renderInner(DrawContext ctx, int mouseX, int mouseY, int screenW, int screenH, boolean hud) {
		Minecraft mc = Minecraft.getInstance();
		Font tr = mc.font;
		hudMode = hud;
		curSW = screenW;
		curSH = screenH;

		PlayerConfig cfg = PlayerConfig.get();
		boolean keep = cfg.pinned || cfg.superPinned;
		if (hud && !keep) {
			// chat is closed and the player is not pinned (any more): the window folds back up, like after pressing the toggle
			if (wasKeep) behind = computeBehind(true);
			open = false;
		}
		if (keepVis < 0f) keepVis = keep ? 1f : 0f;

		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0.016f : Math.min(0.1f, (now - lastNanos) / 1_000_000_000f);
		lastNanos = now;
		float target = open ? 1f : 0f;
		anim += (target - anim) * (1f - (float) Math.exp(-dt * 13f));
		if (Math.abs(target - anim) < 0.002f) anim = target;

		// chrome / keepVis = progress 0..1 that moves in a straight line in time; what is drawn uses the eased value,
		// which slows down smoothly and ends EXACTLY on the target (no jump in the last pixel)
		float slideStep = dt / SLIDE_TIME;
		chrome = approach(chrome, hud ? 0f : 1f, slideStep);
		keepVis = approach(keepVis, (!hud || keep) ? 1f : 0f, slideStep);

		// dragging the resize button changes the size (and the place, so the opposite corner stays where it is)
		if (resizing && !hud) applyResize(mouseX, mouseY, screenW, screenH);

		// lh = what stays with the chat closed, lc = the full chat layout. While the parts are still sliding away we keep the chat layout.
		Layout lh = layout(screenW, screenH, anim, true);
		Layout lc = layout(screenW, screenH, anim, false);
		Layout l = (!hud || chrome > 0f) ? lc : lh;
		L = l;

		if (browser != null) {
			// keep browser size in sync with the window (after changing GUI scale or the size setting)
			int bw = (int) ((l.w - 2) * l.scale), bh = (int) ((l.winFull - 2) * l.scale);
			if (bw != lastBw || bh != lastBh) {
				long nowMs = System.currentTimeMillis();
				// while you drag the resize button the page is re-fitted up to 40 times per second, and exactly at the end
				if (!resizing || nowMs - lastResizeMs > 25) {
					lastResizeMs = nowMs;
					lastBw = bw;
					lastBh = bh;
					browser.resize(bw, bh);
				}
			}
			// page zoom from the settings
			double zl = zoomLevel();
			if (Double.isNaN(lastZoomLevel) || Math.abs(zl - lastZoomLevel) > 0.01) {
				lastZoomLevel = zl;
				browser.setZoomLevel(zl);
			}
			// ask the page what is playing
			long ms = System.currentTimeMillis();
			// the page helper installs itself only once per page; a new page needs it again
			if (ms - lastShimMs > 2500) {
				lastShimMs = ms;
				injectShim();
				applyResolution();
			}
			if (ms - lastPollMs > (ms < fastPollUntil ? 80 : 350)) {
				lastPollMs = ms;
				runJs(POLL_JS);
			}
			// some sites reset the volume on every new song, so we set it again
			if (PlayerConfig.get().volume < 100 && ms - lastVolMs > 1500) {
				lastVolMs = ms;
				applyVolume();
			}
			// mouse movement (hover effects, dragging sliders)
			if (!hud && anim > 0.95f && (insideBrowser(mouseX, mouseY) || pressed)) {
				int sx = bx(mouseX), sy = by(mouseY);
				if (sx != lastSentX || sy != lastSentY) {
					lastSentX = sx;
					lastSentY = sy;
					browser.sendMouseMove(sx, sy);
				}
			}
		}

		if (draggingVolume && !hud) setVolumeFromY(mouseY, l);

		if (l.winH > 0) drawWindow(ctx, tr, l);

		// parts that only exist in the chat slide with "chrome"; parts that also stay when pinned slide with "visKeep"
		float chromeE = smooth(chrome);
		float visExtra = chromeE;
		float visKeep = Math.max(chromeE, smooth(keepVis));

		// settings: how visible the bar, the pin, the buttons and the volume slider are (everything between here and the end)
		float uiA = PlayerConfig.get().uiOpacity / 100f;
		if (uiA < 1f) {
			ctx.setAlpha(uiA);
		}
		try {
		// gray bar with the song name
		boolean barKeeps = lh.textBar;
		int barW = barKeeps ? Math.round(lh.tickerW + (lc.tickerW - lh.tickerW) * chromeE) : l.tickerW;
		int barX = barKeeps ? Math.round(lh.barX + (lc.barX - lh.barX) * chromeE) : l.barX;
		if (barKeeps) {
			// the pin hint (pin hotkey with the chat closed) pops up at the right end of the bar: the bar gets shorter for a
			// moment, like when the chat opens, and grows back when the hint is gone
			float fp = flashFactor(pinFlashShown, pinFlashLast, System.currentTimeMillis(), pinFlashOut);
			if (fp > 0f) {
				int cut = Math.round((lh.tickerH + lh.gap) * fp * (1f - chromeE));
				barW -= cut;
				if (lc.mirror) barX += cut;   // the player is on the right: the pin hint comes up at the LEFT end of the bar
			}
		}
		if (l.rowH > 0 && l.textBar && barW > 0
				&& beginSlide(ctx, l, barKeeps ? visKeep : visExtra, false, l.tickY, l.tickerH)) {
			drawBar(ctx, tr, l, hud, barX, barW);
			endSlide(ctx);
		}

		// arrow button + pin (only in the chat)
		if (l.pinW > 0 && beginSlide(ctx, l, visExtra, false, l.tickY, l.tickerH)) {
			drawCluster(ctx, l, mouseX, mouseY);
			endSlide(ctx);
		}

		// prev / play / next
		if (l.btnCount > 0 && beginSlide(ctx, l, lh.btnCount > 0 ? visKeep : visExtra, false, l.btnY, l.btnH)) {
			drawButtons(ctx, l, mouseX, mouseY);
			endSlide(ctx);
		}

		// volume slider
		if (l.volVisible && beginSlide(ctx, l, lh.volVisible ? visKeep : visExtra, true, 0, 0)) {
			drawVolume(ctx, l, mouseX, mouseY);
			endSlide(ctx);
		}

		} finally {
			if (uiA < 1f) {
				ctx.setAlpha(1f);
			}
		}

		wasKeep = keep;
	}

	private void setVolumeFromY(double my, Layout l) {
		int inner = Math.max(1, l.vh - 2);
		int v = (int) Math.round((l.vy + l.vh - 1 - my) * 100.0 / inner);
		setVolume(v);
	}

	private void setVolume(int v) {
		PlayerConfig c = PlayerConfig.get();
		v = Math.max(0, Math.min(100, v));
		if (v != c.volume) {
			c.volume = v;
			applyVolume();
		}
	}

	private void drawVolume(DrawContext ctx, Layout l, int mx, int my) {
		boolean hover = draggingVolume || inside(mx, my, l.vx, l.vy, l.vw, l.vh);
		drawVolumeBar(ctx, l.vx, l.vy, l.vw, l.vh, PlayerConfig.get().volume, hover, 1f, l.s);
	}

	/** The vertical volume slider with rounded edges. alpha = 1 normal, less = see-through (for the short hint). */
	private static void drawVolumeBar(DrawContext ctx, int x, int y, int w, int h, int vol, boolean hover, float a, float s) {
		int r = Math.max(2, w / 3);   // roundish corners, but not a full oval like the song bar
		fillRound(ctx, x, y, w, h, r, alpha(VOL_RIM, a));
		fillRound(ctx, x + 1, y + 1, w - 2, h - 2, Math.max(0, r - 1), alpha(VOL_TRACK, a));
		int inner = h - 2;
		int fillH = Math.round(inner * vol / 100f);
		if (fillH > 0) {
			fillRound(ctx, x + 1, y + h - 1 - fillH, w - 2, fillH, Math.max(0, Math.min(r - 1, fillH / 2)),
					alpha(hover ? VOL_GREEN_HOVER : VOL_GREEN, a));
		}
		// the knob is a rounded bar across the slider
		int kh = Math.max(5, Math.round(6 * s));
		int ky = Math.max(y + 1, Math.min(y + h - 1 - kh, y + h - 1 - fillH - kh / 2));
		fillRound(ctx, x, ky, w, kh, kh / 2, alpha(0xFF0E0F12, a));
		fillRound(ctx, x + 1, ky + 1, w - 2, kh - 2, Math.max(0, kh / 2 - 1), alpha(hover ? VOL_KNOB_HOVER : VOL_KNOB, a));
	}

	/** Multiplies the transparency of a colour (0xAARRGGBB) by f (0..1). */
	private static int alpha(int argb, float f) {
		if (f >= 1f) return argb;
		int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, f));
		return (a << 24) | (argb & 0xFFFFFF);
	}

	private void drawWindow(DrawContext ctx, Font tr, Layout l) {
		int h = l.winH;
		float winA = PlayerConfig.get().windowOpacity / 100f;
		ctx.fill(l.x0, l.winTop, l.x0 + l.w, l.winTop + h, alpha(focused ? VOL_GREEN : 0xFF000000, winA));
		ctx.fill(l.x0 + 1, l.winTop + 1, l.x0 + l.w - 1, l.winTop + h - 1, alpha(0xFF121212, winA));

		ctx.enableScissor(l.x0 + 1, l.winTop + 1, l.x0 + l.w - 1, l.winTop + h - 1);
		if (browser != null) {
			drawBrowserTexture(ctx, l.x0 + 1, l.texTop + 1, l.w - 2, l.winFull - 2);
		} else if (!Rinku.isInitialized()) {
			List<FormattedCharSequence> lines = tr.split(Component.translatable("mediarift.player.notready"), l.w - 12);
			for (int i = 0; i < Math.min(6, lines.size()); i++) {
				ctx.drawText(tr, lines.get(i), l.x0 + 6, l.texTop + 8 + i * 10, 0xFFFFAA55, false);
			}
		} else {
			ctx.drawText(tr, Component.translatable("mediarift.player.loading"), l.x0 + 6, l.texTop + 8, 0xFFAAAAAA, false);
		}
		ctx.disableScissor();
	}

	private void drawBrowserTexture(DrawContext ctx, int x, int y, int ww, int hh) {
		// 1.21.11: Rinku gives the picture as a normal Minecraft texture, and the game draws it in its own order
		Identifier id = browser.getTextureIdentifier();
		if (id == null || !browser.isTextureReady()) return;
		int a = Math.round(255 * PlayerConfig.get().windowOpacity / 100f);
		ctx.raw().blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, ww, hh, ww, hh, (a << 24) | 0xFFFFFF);
	}

	/** The oval bar with the scrolling song name. */
	private void drawBar(DrawContext ctx, Font tr, Layout l, boolean hud, int barX, int barW) {
		int y = l.tickY;
		float s = l.s;
		MatrixStack m = ctx.getMatrices();

		int r = l.tickerH / 2;
		fillRound(ctx, barX, y, barW, l.tickerH, r, RIM);
		fillRound(ctx, barX + 1, y + 1, barW - 2, l.tickerH - 2, Math.max(0, r - 1), BAR_FILL);

		String text = nowPlaying;
		if (text == null || text.isEmpty()) {
			if (hud) text = "MediaRift";
			else if (browser == null) text = Component.translatable("mediarift.player.open").getString();
			else text = Component.translatable("mediarift.player.idle").getString();
		}
		String unit = text + "      *      ";
		float tw = Math.max(1f, tr.width(unit) * s);
		float off = (float) ((System.currentTimeMillis() * 0.028 * s) % tw);
		// the round ends of the bar are kept free, so the text does not stick out of them
		int inset = Math.max(4, r - 1);
		int left = barX + inset, right = barX + barW - inset;
		float startX = SCROLL_RIGHT ? left + off - tw : left - off;
		float ty = y + (l.tickerH - 8 * s) / 2f;

		// the clip area is in screen pixels, so it has to move together with the sliding bar
		ctx.enableScissor(left + slideDx, y + 1 + slideDy, right + slideDx, y + l.tickerH - 1 + slideDy);
		for (float x = startX; x < right; x += tw) {
			m.push();
			m.translate(x, ty, 0f);
			m.scale(s, s, 1f);
			ctx.drawText(tr, unit, 0, 0, TEXT_COLOR, false);
			m.pop();
		}
		ctx.disableScissor();
	}

	/** The resize button, the small arrow button (when the song bar is off) and the pin. Only in the chat. */
	private void drawCluster(DrawContext ctx, Layout l, int mx, int my) {
		int y = l.tickY;
		float s = l.s;
		MatrixStack m = ctx.getMatrices();
		PlayerConfig c = PlayerConfig.get();

		// the resize button: press it and pull with the mouse (it grows out while the window opens)
		if (l.rszW > 2) {
			boolean hov = resizing || inside(mx, my, l.rszX, y, l.rszW, l.tickerH);
			drawRoundButton(ctx, l.rszX, y, l.rszW, l.tickerH, hov, 1f);
			float grow = l.rszW / (float) l.tickerH;
			m.push();
			m.translate(l.rszX + l.rszW / 2f, y + l.tickerH / 2f, 0f);
			m.scale(s * grow, s * grow, 1f);
			drawResizeIcon(ctx, l.grabRight, TEXT_COLOR);
			m.pop();
		}

		// without the text bar: a small button that opens / closes the window
		if (l.hasToggle) {
			drawRoundButton(ctx, l.toggleX, y, l.pinW, l.tickerH, inside(mx, my, l.toggleX, y, l.pinW, l.tickerH), 1f);
			m.push();
			m.translate(l.toggleX + l.pinW / 2f, y + l.tickerH / 2f, 0f);
			m.scale(s, s, 1f);
			for (int r = 0; r < 4; r++) {
				if (open) ctx.fill(-3 + r, 1 - r, 4 - r, 2 - r, 0xFFFFFFFF);   // arrow up
				else ctx.fill(-3 + r, -2 + r, 4 - r, -1 + r, 0xFFFFFFFF);       // arrow down
			}
			m.pop();
		}

		// the pin: white = off, green = pinned, purple = super pin
		drawRoundButton(ctx, l.pinX, y, l.pinW, l.tickerH, inside(mx, my, l.pinX, y, l.pinW, l.tickerH), 1f);
		int mode = c.pinMode();
		int color = mode == 2 ? 0xFFC259FF : (mode == 1 ? 0xFF55FF55 : 0xFFFFFFFF);
		m.push();
		m.translate(l.pinX + l.pinW / 2f, y + l.tickerH / 2f, 0f);
		m.scale(s, s, 1f);
		drawTack(ctx, color);
		if (mode == 2) {
			// a little spark = "super"
			ctx.fill(3, -7, 4, -4, color);
			ctx.fill(2, -6, 5, -5, color);
		}
		m.pop();
	}

	/** Double arrow of the resize button, drawn around (0, 0). nwse = true: arrow from top-left to bottom-right, false: mirrored. */
	private static void drawResizeIcon(DrawContext ctx, boolean nwse, int color) {
		int sx = nwse ? 1 : -1;
		for (int i = -3; i <= 3; i++) dot(ctx, i, i, sx, color);
		dot(ctx, 2, 3, sx, color);
		dot(ctx, 1, 3, sx, color);
		dot(ctx, 3, 2, sx, color);
		dot(ctx, 3, 1, sx, color);
		dot(ctx, -2, -3, sx, color);
		dot(ctx, -1, -3, sx, color);
		dot(ctx, -3, -2, sx, color);
		dot(ctx, -3, -1, sx, color);
	}

	/** One pixel at (x, y); with sx = -1 the picture is mirrored left-right. */
	private static void dot(DrawContext ctx, int x, int y, int sx, int color) {
		int px = sx > 0 ? x : -x - 1;
		ctx.fill(px, y, px + 1, y + 1, color);
	}

	/** A little thumbtack, drawn around (0, 0). */
	private static void drawTack(DrawContext ctx, int color) {
		ctx.fill(-3, -5, 3, -4, color);
		ctx.fill(-2, -4, 2, 0, color);
		ctx.fill(-4, 0, 4, 1, color);
		ctx.fill(0, 1, 1, 5, color);
	}

	private static boolean insideCircle(double mx, double my, double cx, double cy, double r) {
		double dx = mx - cx, dy = my - cy;
		return dx * dx + dy * dy <= r * r;
	}

	private void drawButtons(DrawContext ctx, Layout l, int mx, int my) {
		int[] acts = PlayerConfig.get().visibleActions();
		int n = Math.min(l.btnCount, Math.min(acts.length, l.btnCx.length));
		if (n == 0) return;
		int d = l.btnH;
		MatrixStack m = ctx.getMatrices();
		for (int k = 0; k < n; k++) {
			int cx = l.btnCx[k];
			int bxx = cx - d / 2;
			boolean hov = insideCircle(mx, my, bxx + d / 2.0, l.btnY + d / 2.0, d / 2.0);
			drawRoundButton(ctx, bxx, l.btnY, d, d, hov, 1f);
			m.push();
			m.translate(bxx + d / 2f, l.btnY + d / 2f, 0f);
			m.scale(l.s, l.s, 1f);
			drawIcon(ctx, acts[k]);
			m.pop();
		}
	}

	/** Icons are drawn around (0, 0); the caller moves and scales the matrix. */
	private void drawIcon(DrawContext ctx, int action) {
		int white = 0xFFFFFFFF;
		if (action == 0) {
			ctx.fill(-4, -4, -2, 5, white);
			triLeft(ctx, -1, 0, 4, white);
		} else if (action == 1) {
			if (playing) {
				ctx.fill(-4, -4, -1, 5, white);
				ctx.fill(1, -4, 4, 5, white);
			} else {
				triRight(ctx, 2, 0, 4, white);
			}
		} else {
			triRight(ctx, 0, 0, 4, white);
			ctx.fill(2, -4, 4, 5, white);
		}
	}

	private static void triLeft(DrawContext ctx, int tipX, int cy, int half, int color) {
		for (int r = -half; r <= half; r++) {
			ctx.fill(tipX + Math.abs(r), cy + r, tipX + half + 1, cy + r + 1, color);
		}
	}

	private static void triRight(DrawContext ctx, int tipX, int cy, int half, int color) {
		for (int r = -half; r <= half; r++) {
			ctx.fill(tipX - half, cy + r, tipX + 1 - Math.abs(r), cy + r + 1, color);
		}
	}

	/** A filled rounded shape. r = corner radius in pixels (r = half of the smaller side makes a circle / an oval). */
	static void fillRound(DrawContext ctx, int x, int y, int ww, int hh, int r, int color) {
		if (ww <= 0 || hh <= 0) return;
		r = Math.max(0, Math.min(r, Math.min(ww, hh) / 2));
		for (int i = 0; i < hh; i++) {
			float cy = i + 0.5f;
			float d = 0f;   // how far into the round corner this row is
			if (cy < r) d = r - cy;
			else if (cy > hh - r) d = cy - (hh - r);
			int inset = 0;
			if (d > 0f) inset = (int) Math.round(r - Math.sqrt(Math.max(0.0, (double) r * r - (double) d * d)));
			if (inset * 2 >= ww) continue;
			ctx.fill(x + inset, y + i, x + ww - inset, y + i + 1, color);
		}
	}

	/** A dark round button (a circle when ww == hh). */
	private static void drawRoundButton(DrawContext ctx, int x, int y, int ww, int hh, boolean hover, float a) {
		int r = Math.min(ww, hh) / 2;
		fillRound(ctx, x, y, ww, hh, r, alpha(RIM, a));
		fillRound(ctx, x + 1, y + 1, ww - 2, hh - 2, Math.max(0, r - 1), alpha(hover ? BTN_HOVER : BTN_FILL, a));
	}

	private static float clampF(float v, float lo, float hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	// ---------------------------------------------- short hints (volume slider, pin)

	/** Shows the volume slider for a moment (even if it is switched off in the settings). */
	public void flashVolume() {
		long now = System.currentTimeMillis();
		if (now > volFlashLast + FLASH_HOLD + volFlashOut) volFlashShown = now;   // a new appearance: play the "slide in" again
		volFlashLast = now;
		volFlashOut = FLASH_OUT;
	}

	/** Shows the pin button for a moment. */
	public void flashPin() {
		long now = System.currentTimeMillis();
		if (now > pinFlashLast + FLASH_HOLD + pinFlashOut) pinFlashShown = now;
		pinFlashLast = now;
		pinFlashOut = FLASH_OUT;
	}

	/**
	 * New "last" time for a hint that must now fade out in CHAT_FADE ms instead of oldOut ms.
	 * If it was already fading, it continues from the same brightness (no jump).
	 */
	private static long fastFade(long last, long oldOut, long now) {
		long outStart = last + FLASH_HOLD;
		float done = now > outStart ? Math.min(1f, (now - outStart) / (float) oldOut) : 0f;
		return now - FLASH_HOLD - Math.round(done * CHAT_FADE);
	}

	/** 0 = not visible, 1 = fully visible. Slides in quickly, stays about 1.3 s, fades out in 0.4 s. */
	private static float flashFactor(long shown, long last, long now, long outMs) {
		if (shown == 0) return 0f;
		float in = Math.min(1f, (now - shown) / (float) FLASH_IN);
		float ease = in * in * (3f - 2f * in);
		float out = 1f;
		long outStart = last + FLASH_HOLD;
		if (now > outStart) out = 1f - Math.min(1f, (now - outStart) / (float) outMs);
		return ease * out;
	}

	/**
	 * Draws the short hints. Called every frame: with the chat closed (HUD hook) and also while the chat is open,
	 * so a hint that is still fading out does not vanish when you open the chat.
	 */
	public void renderFlashes(DrawContext ctx, int screenW, int screenH, boolean chat) {
		ctx.draw();
		MatrixStack fm = ctx.getMatrices();
		fm.push();
		fm.translate(0f, 0f, lift);
		try {
			renderFlashesInner(ctx, screenW, screenH, chat);
		} finally {
			ctx.draw();
			fm.pop();
		}
	}

	private void renderFlashesInner(DrawContext ctx, int screenW, int screenH, boolean chat) {
		long now = System.currentTimeMillis();
		float fv = flashFactor(volFlashShown, volFlashLast, now, volFlashOut);
		float fp = flashFactor(pinFlashShown, pinFlashLast, now, pinFlashOut);
		if (fv <= 0f && fp <= 0f) return;

		PlayerConfig c = PlayerConfig.get();
		Font tr = Minecraft.getInstance().font;
		Layout l = layout(screenW, screenH, anim, !chat);
		MatrixStack m = ctx.getMatrices();

		// is the real slider on screen right now?
		boolean realBar = chat ? l.volVisible : ((c.pinned || c.superPinned) && l.volVisible);
		if (fv > 0f) {
			boolean onRight = l.volRight;

			// the slider itself pops up only when it is not already on screen
			if (!realBar || chat) {   // in the chat it is drawn too, while it fades out behind the sliding real slider
				float in = Math.min(1f, (now - volFlashShown) / (float) FLASH_IN);
				float pop = 0.6f + 0.4f * (in * in * (3f - 2f * in));   // grows a little while it fades in, like the pin
				m.push();
				m.translate(l.vx + l.vw / 2f, l.vy + l.vh / 2f, 0f);
				m.scale(pop, pop, 1f);
				drawVolumeBar(ctx, -l.vw / 2, -l.vh / 2, l.vw, l.vh, c.volume, false, fv, l.s);
				m.pop();
			}

			// the number is always shown (also when the slider is on screen all the time)
			if (fv > 0.1f) {
				// the number goes under the slider and grows AWAY from the player, so it never covers the window
				String t = c.volume + "%";
				int tw = tr.width(t);
				int tx = onRight ? l.vx : l.vx + l.vw - tw;
				int ty = Math.min(l.vy + l.vh + 3, screenH - 12);
				ctx.drawTextWithShadow(tr, t, tx, ty, alpha(0xFFFFFFFF, fv));
			}
		}

		// pin: a small pin button that pops up where the pin button is in the chat
		// (in the chat the real pin button is there, so nothing extra is needed)
		if (fp > 0f) {
			int size = l.tickerH;
			float in = Math.min(1f, (now - pinFlashShown) / (float) FLASH_IN);
			float pop = 0.6f + 0.4f * (in * in * (3f - 2f * in));
			int mode = c.pinMode();
			int color = mode == 2 ? 0xFFC259FF : (mode == 1 ? 0xFF55FF55 : 0xFFFFFFFF);

			m.push();
			m.translate(l.fpinX + size / 2f, l.tickY + size / 2f, 0f);
			m.scale(pop, pop, 1f);
			drawRoundButton(ctx, -size / 2, -size / 2, size, size, false, fp);
			m.push();
			m.scale(l.s, l.s, 1f);
			drawTack(ctx, alpha(color, fp));
			if (mode == 2) {
				ctx.fill(3, -7, 4, -4, alpha(color, fp));
				ctx.fill(2, -6, 5, -5, alpha(color, fp));
			}
			m.pop();
			m.pop();
		}
	}

	/** Reloads the page. @return false if the player has not been started yet. */
	public boolean reloadPage() {
		if (browser == null) return false;
		browser.reload();
		return true;
	}

	// ------------------------------------------------- actions (buttons and hotkeys)

	/** 0 = back, 1 = play / pause, 2 = forward. Used by the buttons and by the hotkeys. */
	public void doAction(int action) {
		if (browser == null) {
			ensureBrowser(); // the page is loading, try again in a moment
			return;
		}
		if (action == 1) playing = !playing; // change the icon at once, the page confirms it a moment later
		runJs(controlJs(ACTION_NAMES[action]));
		lastPollMs = 0;
		fastPollUntil = System.currentTimeMillis() + 3000;
	}

	/**
	 * Next / previous video in endless feeds (YouTube Shorts, TikTok, Instagram Reels). dir = 1 next, -1 previous.
	 * The mouse wheel does not switch these videos, so there is a hotkey. The page is asked to move by script.
	 */
	public void shortsStep(int dir) {
		if (browser == null) {
			ensureBrowser();
			return;
		}
		runJs(SHORTS_JS.replace("__DIR__", dir > 0 ? "1" : "-1"));
	}

	private static final String SHORTS_JS = """
			(function(dir){
			 try {
			  var host = location.hostname;
			  var key = dir > 0 ? 'ArrowDown' : 'ArrowUp';
			  var code = dir > 0 ? 40 : 38;
			  var isYt = host.indexOf('youtube.com') >= 0;
			  var isTt = host.indexOf('tiktok.com') >= 0;
			  if (isYt) {
			    var b = document.querySelector(dir > 0 ? '#navigation-button-down button' : '#navigation-button-up button');
			    if (b) { b.click(); return; }
			  }
			  if (isTt) {
			    var tb = document.querySelector(dir > 0 ? 'button[data-e2e="arrow-right"]' : 'button[data-e2e="arrow-left"]');
			    if (tb) { tb.click(); return; }
			  }
			  if (isYt || isTt) {
			    var tgt = (document.activeElement && document.activeElement !== document.body) ? document.activeElement : document.body;
			    ['keydown', 'keyup'].forEach(function(type) {
			      tgt.dispatchEvent(new KeyboardEvent(type, {key: key, code: key, keyCode: code, which: code, bubbles: true, cancelable: true}));
			    });
			    return;
			  }
			  var best = null, bestD = 1e9, mid = window.innerHeight / 2;
			  Array.prototype.slice.call(document.querySelectorAll('video')).forEach(function(v) {
			    var r = v.getBoundingClientRect();
			    if (r.height < 50) return;
			    var d = Math.abs((r.top + r.bottom) / 2 - mid);
			    if (d < bestD) { bestD = d; best = v; }
			  });
			  var el = best ? best.parentElement : null;
			  while (el && el !== document.documentElement) {
			    var oy = getComputedStyle(el).overflowY;
			    if ((oy === 'auto' || oy === 'scroll') && el.scrollHeight > el.clientHeight + 5) {
			      el.scrollBy({top: dir * el.clientHeight, behavior: 'smooth'});
			      return;
			    }
			    el = el.parentElement;
			  }
			  window.scrollBy({top: dir * window.innerHeight, behavior: 'smooth'});
			 } catch (e) {}
			})(__DIR__);
			""";

	/** Opens / closes the music window (you can only see it when the player is pinned or the chat is open). */
	public void toggleWindow() {
		open = !open;
		if (open) ensureBrowser();
		else setFocused(false);
	}

	/** Page back, like the back arrow of a browser. @return 0 = done, 1 = player not started, 2 = nowhere to go back to. */
	public int goBack() {
		if (browser == null) return 1;
		if (!browser.canGoBack()) return 2;
		browser.goBack();
		return 0;
	}

	/** Page forward, like the forward arrow of a browser. @return 0 = done, 1 = player not started, 2 = nowhere to go. */
	public int goForward() {
		if (browser == null) return 1;
		if (!browser.canGoForward()) return 2;
		browser.goForward();
		return 0;
	}

	/** @return the new volume (0..100). */
	public int changeVolume(int delta) {
		setVolume(PlayerConfig.get().volume + delta);
		PlayerConfig.get().save();
		return PlayerConfig.get().volume;
	}

	// ---------------------------------------------------------- commands (/sb)

	/** @return a message for the player. */
	public String loadUrl(String url) {
		if (!Rinku.isInitialized()) return "The browser (Rinku) is not ready yet.";
		open = true;
		ensureBrowser();
		if (browser == null) return "Could not start the browser.";
		browser.loadURL(url);
		return "Opening " + url;
	}

	public String reload() {
		if (browser == null) return "The player window is not open yet.";
		browser.reload();
		return "Reloading...";
	}

	/** Opens a site AND remembers it as the page that opens first next time. */
	public String openSite(String url) {
		rememberHome(url);
		return loadUrl(url);
	}

	// ------------------------------------------------------------------ input

	/** @return true if the click was used by the player (chat must ignore it). */
	public boolean mouseClicked(double mx, double my, int button) {
		Layout l = L;
		if (l == null) return false;
		if (l.volVisible && inside(mx, my, l.vx, l.vy, l.vw, l.vh)) {
			if (button == InputConstants.MOUSE_BUTTON_LEFT) {
				draggingVolume = true;
				setVolumeFromY(my, l);
			}
			return true;
		}
		boolean onMedia = false;
		if (l.btnCount > 0) {
			for (int cx : l.btnCx) {
				if (insideCircle(mx, my, cx, l.btnY + l.btnH / 2.0, l.btnH / 2.0 + 1)) onMedia = true;
			}
		}
		boolean onSmall = onMedia || (l.pinW > 0 && (inside(mx, my, l.pinX, l.tickY, l.pinW, l.tickerH)
				|| (l.rszW > 0 && inside(mx, my, l.rszX, l.tickY, l.rszW, l.tickerH))
				|| (l.hasToggle && inside(mx, my, l.toggleX, l.tickY, l.pinW, l.tickerH))));
		if (!onSmall && !inside(mx, my, l.x0, l.top, l.w, l.bottom - l.top)) {
			setFocused(false); // clicked somewhere else: keyboard goes back to the chat
			return false;
		}

		// prev / play / next
		if (l.btnCount > 0) {
			int[] acts = PlayerConfig.get().visibleActions();
			int n = Math.min(l.btnCount, Math.min(acts.length, l.btnCx.length));
			if (n > 0) {
				int d = l.btnH;
				for (int k = 0; k < n; k++) {
					int cx = l.btnCx[k];
					if (insideCircle(mx, my, cx - d / 2.0 + d / 2.0, l.btnY + d / 2.0, d / 2.0 + 1)) {
						if (button != InputConstants.MOUSE_BUTTON_LEFT) return true;
						if (browser == null) {
							open = true;
							ensureBrowser();
						} else {
							doAction(acts[k]);
						}
						return true;
					}
				}
			}
		}

		// the pin: off -> green -> purple -> off
		if (inside(mx, my, l.pinX, l.tickY, l.pinW, l.tickerH)) {
			if (button == InputConstants.MOUSE_BUTTON_LEFT) {
				PlayerConfig c = PlayerConfig.get();
				c.setPinMode((c.pinMode() + 1) % 3);
				c.save();
			}
			return true;
		}

		// the resize button: press it and drag
		if (l.rszW > 2 && anim > 0.95f && inside(mx, my, l.rszX, l.tickY, l.rszW, l.tickerH)) {
			if (button == InputConstants.MOUSE_BUTTON_LEFT) startResize(mx, my, l);
			return true;
		}

		// the gray bar (or the small arrow button when the bar is off): opens / closes the window
		boolean onBar = l.tickerW > 0 && inside(mx, my, l.barX, l.tickY, l.tickerW, l.tickerH);
		boolean onToggle = l.hasToggle && inside(mx, my, l.toggleX, l.tickY, l.pinW, l.tickerH);
		if (onBar || onToggle) {
			if (button == InputConstants.MOUSE_BUTTON_LEFT) {
				open = !open;
				if (open) ensureBrowser();
				if (!open) setFocused(false);
			}
			return true;
		}

		if (browser != null && insideBrowser(mx, my)) {
			setFocused(true);
			pressed = true;
			int sx = bx(mx), sy = by(my);
			browser.sendMouseMove(sx, sy);
			browser.sendMousePress(sx, sy, button);
		}
		return true;
	}

	/** Remembers where the drag started. The side that is NOT pulled stays where it is. */
	private void startResize(double mx, double my, Layout l) {
		PlayerConfig c = PlayerConfig.get();
		resizing = true;
		rsMx0 = (int) mx;
		rsMy0 = (int) my;
		rsX0 = l.x0;
		rsW0 = l.w;
		rsH0 = l.winFull;
		rsTop0 = l.winTop;
		rsTickY0 = l.tickY;
		rsStackH = l.bottom - l.tickY;
		rsGrabRight = l.grabRight;
		rsUp = !(c.posY < 50);   // lower half of the screen: the window grows UP (the bar stays), pulling up makes it taller
		rsScale = l.scale;
	}

	/** Called every frame while the resize button is held. Changes width / height (and the left-right place) in the settings. */
	private void applyResize(int mx, int my, int sw, int sh) {
		PlayerConfig c = PlayerConfig.get();
		float s = c.sizePercent / 100f * fitFactor();
		float scale = (float) rsScale;
		int dx = mx - rsMx0, dy = my - rsMy0;

		// width: the side with the button follows the mouse
		int minW = Math.max(80, (int) Math.ceil(MIN_REAL_W / scale));
		int maxW = rsGrabRight ? sw - MARGIN - rsX0 : rsX0 + rsW0 - MARGIN;
		int newW = rsW0 + (rsGrabRight ? dx : -dx);
		newW = Math.max(minW, Math.min(Math.max(minW, maxW), newW));

		// height: in the upper half the bottom edge follows the mouse, in the lower half the top edge does
		int minH = Math.max(40, (int) Math.ceil(MIN_REAL_H / scale));
		int maxH = rsUp ? rsTickY0 - MARGIN : sh - MARGIN - rsTop0 - rsStackH;
		int newH = rsH0 + (rsUp ? -dy : dy);
		newH = Math.max(minH, Math.min(Math.max(minH, maxH), newH));

		c.widthPercent = clampF(newW * scale * 100f / (REAL_W * s), 20f, 400f);
		c.heightPercent = clampF(newH * scale * 100f / (REAL_H * s), 20f, 400f);

		// the layout rounds to whole pixels, so we do the same to know the real width, and keep the other side in place
		int wAct = Math.max(80, Math.round(REAL_W * s * c.widthPercent / 100f / scale));
		int x0 = rsGrabRight ? rsX0 : rsX0 + rsW0 - wAct;
		float free = sw - wAct - 2 * MARGIN;
		c.posX = free > 0.5f ? clampF((x0 - MARGIN) * 100f / free, 0f, 100f) : 0f;
	}

	public boolean mouseReleased(double mx, double my, int button) {
		if (resizing) {
			resizing = false;
			PlayerConfig.get().save();
			return true;
		}
		if (draggingVolume) {
			draggingVolume = false;
			PlayerConfig.get().save();
			return true;
		}
		if (browser != null && pressed && L != null) {
			pressed = false;
			browser.sendMouseRelease(bx(mx), by(my), button);
			return true;
		}
		return false;
	}

	public boolean mouseScrolled(double mx, double my, double vertical) {
		Layout l = L;
		if (l != null && l.volVisible && inside(mx, my, l.vx, l.vy, l.vw, l.vh)) {
			setVolume(PlayerConfig.get().volume + (vertical > 0 ? 5 : -5));
			PlayerConfig.get().save();
			return true;
		}
		if (browser != null && insideBrowser(mx, my)) {
			browser.sendMouseWheel(bx(mx), by(my), vertical, 0);
			return true;
		}
		return false;
	}

	public boolean keyPressed(int key, int scancode, int modifiers) {
		if (!focused || browser == null) return false;
		if (key == InputConstants.KEY_ESCAPE) { // ESC: leave the browser, then chat closes as usual
			setFocused(false);
			return false;
		}
		browser.sendKeyPress(key, 0L, modifiers);   // 26.3 (SDL): Rinku wants 0 here, it finds the real key code itself
		return true;
	}

	public boolean keyReleased(int key, int scancode, int modifiers) {
		if (!focused || browser == null) return false;
		if (key == InputConstants.KEY_ESCAPE) return false;
		browser.sendKeyRelease(key, 0L, modifiers);
		return true;
	}

	public boolean charTyped(char chr, int modifiers) {
		if (!focused || browser == null || chr == 0) return false;
		browser.sendKeyTyped(chr, modifiers);
		return true;
	}
}
