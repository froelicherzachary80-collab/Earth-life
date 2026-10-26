package earthlife.mod.client.cinema;

import com.mojang.blaze3d.platform.NativeImage;
import earthlife.core.cinema.media.Auto4DXEngine;
import earthlife.core.cinema.media.AudioAnalyzer;
import earthlife.core.cinema.media.FrameAnalyzer;
import earthlife.core.cinema.media.StereoConverter;
import earthlife.mod.EarthLifeMod;
import earthlife.mod.cinema.CinemaScreenEntity;
import earthlife.mod.net.Payloads;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Web cinemas: real Chromium browsers on cinema screens, through the optional MCEF Modern mod (Chromium Embedded
 * Framework for Fabric 26.3; it downloads its Chromium runtime on first use). EarthLife talks to MCEF by reflection, so
 * the mod works - without web cinemas - when MCEF isn't installed.
 *
 * Only the web room you are standing in has a live browser (leaving the room closes it and stops its sound). Its frames
 * are copied (from MCEF's paint callback) into a texture on the cinema screen; in the 3D web room (B) they go through
 * the same 2D-to-3D anaglyph conversion as films, and in the 4DX room (C) through the 4DX picture analysis (Chromium
 * plays the sound itself, so 4DX in web rooms works from the picture only). Sites with DRM-protected video (most paid
 * streaming services) can't be played or captured; MCEF's Chromium has no H.264/AAC decoders, so YouTube (VP9/AV1) works
 * but some sites that only offer H.264 video won't.
 *
 * Privacy: each player's browser runs on their own PC with its own sign-ins; what you browse is never sent to the server
 * or other players, unless you press "Show to the room", which shares only the page address.
 */
public final class BrowserScreens {
    private BrowserScreens() {}

    public static boolean available() { return FabricLoader.getInstance().isModLoaded("mcef-modern"); }

    // ------------------------------------------------------------------ MCEF by reflection
    static Class<?> apiClass, browserClass;
    static Method mInitialize, mStage, mPercent, mFuture, mCreate, mResize, mFocus, mClick, mRelease, mScroll, mMove, mKey, mKeyUp, mChar, mClose, mCef;
    static Method cLoad, cBack, cForward, cReload, cUrl, cJs;
    static volatile String initError;
    static volatile Object api;
    static volatile CompletableFuture<?> initFuture;

    static synchronized boolean bind() {
        if (browserClass != null) return true;
        if (!available() || initError != null) return false;
        try {
            apiClass = Class.forName("net.dimaskama.mcef.api.MCEFApi");
            browserClass = Class.forName("net.dimaskama.mcef.api.MCEFBrowser");
            Class<?> init = Class.forName("net.dimaskama.mcef.api.MCEFApi$Initialization");
            mInitialize = apiClass.getMethod("initialize");
            mStage = init.getMethod("getStage"); mPercent = init.getMethod("getPercentage"); mFuture = init.getMethod("getFuture");
            mCreate = apiClass.getMethod("createBrowser", String.class, boolean.class);
            mResize = browserClass.getMethod("resize", int.class, int.class);
            mFocus = browserClass.getMethod("setFocus", boolean.class);
            mClick = browserClass.getMethod("onMouseClicked", net.minecraft.client.input.MouseButtonEvent.class, boolean.class);
            mRelease = browserClass.getMethod("onMouseReleased", net.minecraft.client.input.MouseButtonEvent.class);
            mScroll = browserClass.getMethod("onMouseScrolled", int.class, int.class, double.class);
            mMove = browserClass.getMethod("onMouseMoved", int.class, int.class);
            mKey = browserClass.getMethod("onKeyPressed", net.minecraft.client.input.KeyEvent.class);
            mKeyUp = browserClass.getMethod("onKeyReleased", net.minecraft.client.input.KeyEvent.class);
            mChar = browserClass.getMethod("onCharTyped", net.minecraft.client.input.CharacterEvent.class);
            mClose = browserClass.getMethod("close");
            mCef = browserClass.getMethod("getCefBrowser");
            Class<?> cef = Class.forName("org.cef.browser.CefBrowser", false, browserClass.getClassLoader());
            cLoad = cef.getMethod("loadURL", String.class); cBack = cef.getMethod("goBack"); cForward = cef.getMethod("goForward");
            cReload = cef.getMethod("reload"); cUrl = cef.getMethod("getURL"); cJs = cef.getMethod("executeJavaScript", String.class, String.class, int.class);
            return true;
        } catch (Throwable t) {
            initError = "MCEF Modern API not recognised (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")";
            EarthLifeMod.LOG.warn("ZMF web cinema: {}", initError);
            return false;
        }
    }

    /** Starts MCEF (first time: downloads Chromium, ~100 MB, into .minecraft). */
    static void startApi() {
        if (api != null || initFuture != null || !bind()) return;
        try {
            Object init = mInitialize.invoke(null);
            initFuture = ((CompletableFuture<?>) mFuture.invoke(init)).whenComplete((a, e) -> {
                if (e != null) { initError = "Chromium failed to start: " + e.getMessage(); EarthLifeMod.LOG.warn("ZMF web cinema: {}", initError); }
                else api = a;
            });
            initObj = init;
        } catch (Throwable t) { initError = "Chromium failed to start: " + t; }
    }
    static Object initObj;

    public static String engineStatus() {
        if (!available()) return "Web cinemas need the MCEF Modern mod (modrinth.com/mod/mcef-modern) in your mods folder";
        if (initError != null) return initError;
        if (api != null) return "Chromium ready";
        if (initObj == null) return "Chromium not started yet";
        try {
            Object st = mStage.invoke(initObj); float pc = (float) mPercent.invoke(initObj);
            return "Chromium: " + st.toString().toLowerCase(Locale.ROOT) + (pc >= 0 ? String.format(Locale.ROOT, " %.0f%%", pc) : "");
        } catch (Throwable t) { return "Chromium starting..."; }
    }

    static Object call(Object target, Method m, Object... args) {
        try { return m.invoke(target, args); } catch (Throwable t) { return null; }
    }

    // ------------------------------------------------------------------ one browser per web room

    public static final class Session {
        final String key;
        public final String roomFormats;
        Object browser;
        String url;                       // what the room shows (from the server)
        public volatile String localUrl;  // what this browser currently shows (may differ after clicking links)
        int w, h;
        volatile byte[] frame;            // latest RGBA frame (from the paint callback), null when consumed
        volatile long lastCopy;
        DynamicTexture texture;
        Identifier textureId;
        public volatile boolean hasFrame;
        public volatile int frames;
        public volatile double volume = 0.8;
        final FrameAnalyzer analyzer = new FrameAnalyzer();
        StereoConverter stereo;
        public final Auto4DXEngine fx = new Auto4DXEngine(new Auto4DXEngine.Settings());
        public volatile FrameAnalyzer.Features features;
        long wantedAt;

        Session(String key, String roomFormats) { this.key = key; this.roomFormats = roomFormats; }

        boolean threeD() { return roomFormats.contains("3D") && CinemaClient.wearingGlasses(); }
    }

    static final Map<String, Session> sessions = new HashMap<>();
    static final Map<Object, Session> byBrowser = Collections.synchronizedMap(new IdentityHashMap<>());

    /** Called each tick for the web room you are in. */
    static void want(CinemaScreenEntity s, Payloads.CinemaShow show) {
        if (!available()) return;
        startApi();
        String url = show.source().substring(4);
        Session ses = sessions.get(s.key());
        if (ses == null) { ses = new Session(s.key(), s.dataValue("fmt")); sessions.put(s.key(), ses); }
        ses.wantedAt = System.currentTimeMillis();
        if (api == null) return;
        int h = Math.max(360, Math.min(1080, CinemaConfig.browserHeight)), w = h * 16 / 9;
        if (ses.browser == null) {
            Object b = call(api, mCreate, url, false);
            if (b == null) { initError = "could not open a browser"; return; }
            call(b, mResize, w, h);
            ses.browser = b; ses.w = w; ses.h = h; ses.url = url; ses.localUrl = url;
            byBrowser.put(b, ses);
        } else if (!url.equals(ses.url)) {
            ses.url = url; ses.localUrl = url;
            Object cef = call(ses.browser, mCef);
            if (cef != null) call(cef, cLoad, url);
        } else if (ses.w != w) { call(ses.browser, mResize, w, h); ses.w = w; ses.h = h; }
    }

    /** MCEF paint callback (CEF thread): copy at most ~30 frames a second of our own browsers. */
    public static void onPaint(Object browser, ByteBuffer bgra, int w, int h) {
        Session s = byBrowser.get(browser);
        if (s == null || bgra == null) return;
        long now = System.nanoTime();
        if (now - s.lastCopy < 33_000_000L) return;
        s.lastCopy = now;
        int n = w * h * 4;
        if (bgra.capacity() < n) return;
        byte[] out = new byte[n];
        bgra.duplicate().position(0).get(out, 0, n);
        for (int i = 0; i < n; i += 4) { byte b = out[i]; out[i] = out[i + 2]; out[i + 2] = b; out[i + 3] = (byte) 255; }    // BGRA -> RGBA
        s.w = w; s.h = h; s.frame = out;
    }

    /** Render thread: newest frame of a web room's browser as a texture (null until the first frame). */
    public static Identifier texture(String key) {
        Session s = sessions.get(key);
        if (s == null) return null;
        byte[] f = s.frame;
        if (f != null) {
            s.frame = null;
            int w = s.w, h = s.h;
            if (f.length == w * h * 4) {
                s.features = s.analyzer.analyze(f, w, h, System.currentTimeMillis());
                if (s.roomFormats.contains("4DX")) for (var ev : s.fx.update(s.features, AudioAnalyzer.Levels.SILENT)) webEvents.add(ev);
                if (s.threeD()) {
                    if (s.stereo == null) s.stereo = new StereoConverter(CinemaConfig.stereo());
                    byte[] o = new byte[f.length];
                    s.stereo.convert(f, o, w, h);
                    f = o;
                }
                if (s.texture == null || s.texture.getPixels() == null || s.texture.getPixels().getWidth() != w || s.texture.getPixels().getHeight() != h) {
                    if (s.textureId != null) Minecraft.getInstance().getTextureManager().release(s.textureId);
                    s.texture = new DynamicTexture(() -> "ZMF web cinema " + key, w, h, false);
                    s.textureId = EarthLifeMod.id("cinema/web_" + key.replace(':', '_').replace('-', '_') + "_" + Integer.toHexString(System.identityHashCode(s.texture)));
                    Minecraft.getInstance().getTextureManager().register(s.textureId, s.texture);
                }
                NativeImage img = s.texture.getPixels();
                MemoryUtil.memByteBuffer(img.getPointer(), w * h * 4).put(0, f, 0, w * h * 4);
                s.texture.upload();
                s.hasFrame = true; s.frames++;
            }
        }
        return s.hasFrame ? s.textureId : null;
    }

    /** 4DX events recognised in web rooms (picture only), consumed by the 4D seats. */
    public static final java.util.concurrent.ConcurrentLinkedQueue<Auto4DXEngine.Event> webEvents = new java.util.concurrent.ConcurrentLinkedQueue<>();

    public static String status(String key) {
        Session s = sessions.get(key);
        if (!available()) return "Install MCEF Modern to use web cinemas";
        if (s == null) return "Step inside to open the page";
        return api == null ? engineStatus() : "Loading the page...";
    }

    public static Session session(String key) { return sessions.get(key); }

    /** Session of the web room the player is in, if its browser is open. */
    public static Session current() {
        var s = CinemaClient.roomScreen();
        return s == null ? null : sessions.get(s.key());
    }

    static void tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> {
            if (now - e.getValue().wantedAt > 1500) { close(e.getValue()); return true; }
            return false;
        });
        for (Session s : sessions.values()) if (s.browser != null && s.frames > 0 && s.frames % 40 == 1) setVolume(s, s.volume * mc.options.getFinalSoundSourceVolume(net.minecraft.sounds.SoundSource.RECORDS));
    }

    static void close(Session s) {
        if (s.browser != null) { byBrowser.remove(s.browser); call(s.browser, mClose); s.browser = null; }
        if (s.textureId != null) { var id = s.textureId; Minecraft.getInstance().execute(() -> Minecraft.getInstance().getTextureManager().release(id)); }
    }

    public static void closeAll() { sessions.values().forEach(BrowserScreens::close); sessions.clear(); }

    // ------------------------------------------------------------------ controls (used by the browser control screen)

    public static void input(Session s, String kind, Object... a) {
        if (s == null || s.browser == null) return;
        switch (kind) {
            case "focus" -> call(s.browser, mFocus, a);
            case "click" -> call(s.browser, mClick, a);
            case "release" -> call(s.browser, mRelease, a);
            case "scroll" -> call(s.browser, mScroll, a);
            case "move" -> call(s.browser, mMove, a);
            case "key" -> call(s.browser, mKey, a);
            case "keyup" -> call(s.browser, mKeyUp, a);
            case "char" -> call(s.browser, mChar, a);
            default -> {}
        }
    }

    public static void navigate(Session s, String url) {
        if (s == null || s.browser == null) return;
        Object cef = call(s.browser, mCef);
        if (cef != null) { call(cef, cLoad, url); s.localUrl = url; }
    }

    public static void back(Session s) { Object c = s == null || s.browser == null ? null : call(s.browser, mCef); if (c != null) call(c, cBack); }
    public static void forward(Session s) { Object c = s == null || s.browser == null ? null : call(s.browser, mCef); if (c != null) call(c, cForward); }
    public static void reload(Session s) { Object c = s == null || s.browser == null ? null : call(s.browser, mCef); if (c != null) call(c, cReload); }

    public static String url(Session s) {
        Object c = s == null || s.browser == null ? null : call(s.browser, mCef);
        Object u = c == null ? null : call(c, cUrl);
        if (u != null) s.localUrl = u.toString();
        return s == null ? "" : s.localUrl == null ? "" : s.localUrl;
    }

    /** Page volume (Chromium plays the sound itself): sets every video/audio element's volume. */
    public static void setVolume(Session s, double v) {
        Object c = s == null || s.browser == null ? null : call(s.browser, mCef);
        if (c == null) return;
        String js = String.format(Locale.ROOT, "document.querySelectorAll('video,audio').forEach(function(e){e.volume=%.2f;});", Math.max(0, Math.min(1, v)));
        call(c, cJs, js, "", 0);
    }

    /** Cinema fullscreen: asks the page to show its main video full-window (YouTube, Twitch and most players support it). */
    public static void cinemaMode(Session s) {
        Object c = s == null || s.browser == null ? null : call(s.browser, mCef);
        if (c == null) return;
        call(c, cJs, "(function(){var v=document.querySelector('video');if(!v)return;var t=v.closest('.html5-video-player')||v.parentElement||v;"
                + "if(t.requestFullscreen)t.requestFullscreen();else if(v.requestFullscreen)v.requestFullscreen();})();", "", 0);
    }

    public static void resize(Session s, int height) {
        CinemaConfig.browserHeight = height; CinemaConfig.save();
        if (s != null && s.browser != null) { call(s.browser, mResize, height * 16 / 9, height); s.w = height * 16 / 9; s.h = height; }
    }
}
