package earthlife.mod.client.cinema;

import com.mojang.blaze3d.platform.NativeImage;
import earthlife.mod.EarthLifeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plays one film on one cinema screen, on this computer: a single FFmpeg process decodes the picture (raw RGBA frames
 * on stdout, scaled for the screen and - for 3D films - converted to red/cyan anaglyph or a 2D eye) and the sound (PCM to
 * a local TCP socket feeding {@link AudioOut}). Frames are shown when the sound clock reaches them; without sound the
 * wall clock paces the film. Also feeds the 4D engine with the loudness and brightness/motion of what's on screen.
 */
public final class VideoPlayer implements AutoCloseable {
    public static final int FPS = 24;

    record Frame(long ptsMs, byte[] rgba, float luma, float motion, earthlife.core.cinema.media.FrameAnalyzer.Features features, int avgRgb) {}

    final String key;
    public final String source, format;
    public final boolean anaglyph;
    public volatile String status = "Connecting...";
    public volatile String error;
    public volatile long durationMs;
    public volatile float aspect = 2.2f;
    public volatile boolean hasAudio;
    /** Live analysis for the 4D effects: loudness of the bass (0..1), loudness overall, picture brightness and motion. */
    public volatile float bass, loud, luma, motion;
    /** ZMF: room capabilities ("IMAX+3D", ...), automatic 2D-to-3D, picture/sound analysis for 4DX, ScreenX walls, screen glow. */
    public final String roomFormats;
    public final boolean auto3d;
    final earthlife.core.cinema.media.FrameAnalyzer analyzer = new earthlife.core.cinema.media.FrameAnalyzer();
    public final earthlife.core.cinema.media.AudioAnalyzer audioLevels = new earthlife.core.cinema.media.AudioAnalyzer(AudioOut.RATE);
    earthlife.core.cinema.media.StereoConverter stereo;
    public volatile earthlife.core.cinema.media.FrameAnalyzer.Features features;
    public volatile int avgRgb = 0x202020;
    public volatile earthlife.core.cinema.media.AudioAnalyzer.Levels shownLevels = earthlife.core.cinema.media.AudioAnalyzer.Levels.SILENT;
    public volatile float convertMs, disparityPx;
    public volatile String depthModel = "";
    final boolean screenX;
    static final int SIDE_W = 128, SIDE_H = 72;
    private volatile byte[] sideL, sideR;
    private DynamicTexture texL, texR;
    private Identifier idL, idR;
    private boolean sidesDirty;
    /** 4DX event recognition runs on the frames as they are shown (so effects are in sync with the picture). */
    public final earthlife.core.cinema.media.Auto4DXEngine fx = new earthlife.core.cinema.media.Auto4DXEngine(fxSettings());
    final java.util.concurrent.ConcurrentLinkedQueue<earthlife.core.cinema.media.Auto4DXEngine.Event> fxEvents = new java.util.concurrent.ConcurrentLinkedQueue<>();
    public volatile boolean analyse4dx;
    /** Soundtrack levels by film time: the sound is decoded ahead of the picture, so 4DX looks up the moment on screen. */
    private final ArrayDeque<Object[]> levelLog = new ArrayDeque<>();
    private long audioFrames;
    private final ArrayDeque<earthlife.core.cinema.media.Auto4DXEngine.Event> pendingFx = new ArrayDeque<>();

    earthlife.core.cinema.media.AudioAnalyzer.Levels levelsAt(long ptsMs) {
        synchronized (levelLog) {
            earthlife.core.cinema.media.AudioAnalyzer.Levels best = null;
            for (Object[] e : levelLog) { if ((long) e[0] <= ptsMs) best = (earthlife.core.cinema.media.AudioAnalyzer.Levels) e[1]; else break; }
            return best != null ? best : levelLog.isEmpty() ? earthlife.core.cinema.media.AudioAnalyzer.Levels.SILENT : (earthlife.core.cinema.media.AudioAnalyzer.Levels) levelLog.peekFirst()[1];
        }
    }

    static earthlife.core.cinema.media.Auto4DXEngine.Settings fxSettings() {
        var s = new earthlife.core.cinema.media.Auto4DXEngine.Settings();
        s.sensitivity = (float) CinemaConfig.fxSensitivity; s.blood = CinemaConfig.gore > 0;
        return s;
    }

    /** Events recognised since the last call. */
    public java.util.List<earthlife.core.cinema.media.Auto4DXEngine.Event> pollEvents() {
        java.util.List<earthlife.core.cinema.media.Auto4DXEngine.Event> l = new java.util.ArrayList<>();
        earthlife.core.cinema.media.Auto4DXEngine.Event e;
        while ((e = fxEvents.poll()) != null) l.add(e);
        return l;
    }

    private int w, h;
    private Process proc;
    private ServerSocket audioServer;
    private final ArrayBlockingQueue<Frame> frames = new ArrayBlockingQueue<>(120);
    private final ArrayDeque<byte[]> pool = new ArrayDeque<>();
    private AudioOut audio;
    private volatile boolean audioEnded, closed;
    private long startPosMs, wallStartNanos = -1;
    private long wallBaseMs;
    private DynamicTexture texture;
    private Identifier textureId;
    private Frame shown;
    private float gain = 1;
    private Thread starter;
    private volatile MediaLibrary.Input input;
    private long lastProgressMs, progressStarted;

    VideoPlayer(String key, String source, String format, boolean anaglyph) { this(key, source, format, anaglyph, ""); }

    VideoPlayer(String key, String source, String format, boolean anaglyph, String roomFormats) {
        this.key = key; this.source = source; this.format = format; this.anaglyph = anaglyph;
        this.roomFormats = roomFormats == null ? "" : roomFormats;
        this.auto3d = anaglyph && "AUTO3D".equals(format);
        this.screenX = this.roomFormats.contains("ScreenX");
        if (auto3d) stereo = new earthlife.core.cinema.media.StereoConverter(CinemaConfig.stereo());
    }

    /** Applies changed 3D settings to the running conversion. */
    public void stereoSettings(earthlife.core.cinema.media.StereoConverter.Settings st) { if (stereo != null) stereo.settings(st); }
    public Identifier sideTexture(boolean left) { return left ? idL : idR; }

    public Identifier texture() { return shown == null ? null : textureId; }
    public int width() { return w; }
    public int height() { return h; }

    /** Starts decoding at a film position (resolving the source, probing and launching FFmpeg on a worker thread). */
    void start(long positionMs, String owner) {
        startPosMs = Math.max(0, positionMs);
        starter = new Thread(() -> {
            try {
                String ff = Ffmpeg.path();
                if (ff == null) throw new IOException("FFmpeg is needed to play films - open the cinema menu [K] and press \"Get FFmpeg\"");
                status = "Finding the film...";
                MediaLibrary.Input in = MediaLibrary.resolve(source, owner);
                input = in;
                if (closed) return;
                status = "Opening...";
                probe(ff, in);
                if (closed) return;
                launch(ff, in);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                status = "Can't play: " + error;
                EarthLifeMod.LOG.info("cinema {}: {}", key, error);
            }
        }, "EarthLife-cinema-start");
        starter.setDaemon(true);
        starter.start();
    }

    private static final Pattern DURATION = Pattern.compile("Duration: (\\d+):(\\d+):(\\d+)\\.(\\d+)");
    private static final Pattern SIZE = Pattern.compile(", (\\d{2,5})x(\\d{2,5})[ ,]");
    private static final Pattern DAR = Pattern.compile("DAR (\\d+):(\\d+)");

    /** Reads the film's streams, size and length (ffmpeg -i prints them and exits). */
    private void probe(String ff, MediaLibrary.Input in) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(ff, "-hide_banner", "-nostdin"));
        cmd.addAll(in.ffmpegArgs());
        cmd.addAll(List.of("-i", in.url()));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        Matcher d = DURATION.matcher(out);
        if (d.find()) durationMs = ((Long.parseLong(d.group(1)) * 60 + Long.parseLong(d.group(2))) * 60 + Long.parseLong(d.group(3))) * 1000;
        int vw = 0, vh = 0; float dar = 0;
        for (String line : out.split("\n")) {
            if (line.contains("Video:") && vw == 0) {
                Matcher m = SIZE.matcher(line + " ");
                if (m.find()) { vw = Integer.parseInt(m.group(1)); vh = Integer.parseInt(m.group(2)); }
                Matcher q = DAR.matcher(line);
                if (q.find() && Integer.parseInt(q.group(2)) > 0) dar = Float.parseFloat(q.group(1)) / Float.parseFloat(q.group(2));
            }
            if (line.contains("Audio:")) hasAudio = true;
        }
        if (vw == 0) {
            String why = out.contains("403") ? "access denied (403)" : out.contains("404") ? "not found (404)" : out.contains("401") ? "not signed in (401)" : "no video stream";
            throw new IOException(why);
        }
        float a = dar > 0 ? dar : vw / (float) vh;
        a = switch (format) { case "SBS" -> a / 2; case "TAB" -> a * 2; default -> a; };     // full side-by-side / top-and-bottom carry two pictures
        aspect = Math.max(1.0f, Math.min(3.0f, a));
        h = Math.max(144, Math.min(1080, Math.min(vh, CinemaConfig.quality))) & ~1;
        w = Math.min(1920, Math.round(h * aspect)) & ~1;
    }

    private String filter() {
        String in = switch (format) { case "SBS" -> "sbsl"; case "HSBS" -> "sbs2l"; case "TAB" -> "abl"; case "HTAB" -> "ab2l"; default -> null; };
        String s3 = in == null ? "" : "stereo3d=" + in + ":" + (anaglyph ? "arcd" : "ml") + ",";
        return s3 + "scale=" + w + ":" + h + ":flags=bilinear,setsar=1,fps=" + FPS + ",format=rgba";
    }

    private void launch(String ff, MediaLibrary.Input in) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(ff, "-nostdin", "-hide_banner", "-loglevel", "error"));
        if (in.url().startsWith("http")) cmd.addAll(List.of("-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5"));
        if (!hasAudio) cmd.add("-re");
        if (startPosMs > 0) cmd.addAll(List.of("-ss", String.format(Locale.ROOT, "%.3f", startPosMs / 1000.0)));
        cmd.addAll(in.ffmpegArgs());
        cmd.addAll(List.of("-i", in.url(), "-map", "0:v:0", "-vf", filter(), "-f", "rawvideo", "pipe:1"));
        if (hasAudio) {
            audio = new AudioOut();
            if (!audio.ok) {
                audio.close(); audio = null;
                cmd.add(cmd.indexOf("-i"), "-re");                                // no sound device: pace by the wall clock
            }
            // the soundtrack is always decoded: played when there is a sound device, and analysed for 4DX either way
            audioServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            audioServer.setReceiveBufferSize(64 * 1024);
            audioServer.setSoTimeout(30_000);
            cmd.addAll(List.of("-map", in.audioMap() + "?", "-ac", "2", "-ar", String.valueOf(AudioOut.RATE), "-f", "s16le", "tcp://127.0.0.1:" + audioServer.getLocalPort()));
        }
        proc = new ProcessBuilder(cmd).start();
        status = "Buffering...";
        Thread err = new Thread(() -> {
            try (var r = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
                String l; while ((l = r.readLine()) != null) if (!l.isBlank()) { EarthLifeMod.LOG.debug("ffmpeg: {}", l); if (error == null && !closed) error = l; }
            } catch (IOException ignored) {}
        }, "EarthLife-cinema-ffmpeg-log");
        err.setDaemon(true); err.start();
        Thread video = new Thread(this::readVideo, "EarthLife-cinema-video");
        video.setDaemon(true); video.start();
        if (audioServer != null) {
            Thread a = new Thread(this::readAudio, "EarthLife-cinema-audio");
            a.setDaemon(true); a.start();
        }
    }

    private byte[] buffer() {
        synchronized (pool) { byte[] b = pool.poll(); if (b != null && b.length == w * h * 4) return b; }
        return new byte[w * h * 4];
    }

    private void recycle(byte[] b) { synchronized (pool) { if (pool.size() < 16) pool.add(b); } }

    private void readVideo() {
        int size = w * h * 4;
        long n = 0;
        byte[] prevSmall = null;
        try (InputStream in = new BufferedInputStream(proc.getInputStream(), 1 << 20)) {
            while (!closed) {
                byte[] b = buffer();
                int off = 0;
                while (off < size) { int k = in.read(b, off, size - off); if (k < 0) { status = "The End"; return; } off += k; }
                // ZMF: picture analysis (4DX events) and automatic 2D-to-3D conversion, on this decoding thread
                var feat = analyzer.analyze(b, w, h, startPosMs + n * 1000 / FPS);
                if (screenX && n % 2 == 0) sides(b);
                int avg = average(b);
                // 4DX recognition on every decoded frame (independent of how fast the client renders);
                // events wait in `pendingFx` until the picture on screen reaches them
                for (var ev : fx.update(feat, levelsAt(feat.ptsMs()))) synchronized (pendingFx) { pendingFx.addLast(ev); if (pendingFx.size() > 64) pendingFx.removeFirst(); }
                if (auto3d && stereo != null) {
                    byte[] out = buffer();
                    stereo.convert(b, out, w, h);
                    convertMs = stereo.lastMicros / 1000f; disparityPx = stereo.lastMeanDisparityPx;
                    if (stereo.depth() != null) depthModel = stereo.depth().lastModel;
                    recycle(b); b = out;
                }
                // tiny analysis for 4D: mean brightness + change against the previous frame on an 8x8 grid
                byte[] small = new byte[64]; long sum = 0;
                for (int gy = 0; gy < 8; gy++) for (int gx = 0; gx < 8; gx++) {
                    int i = ((gy * h / 8 + h / 16) * w + gx * w / 8 + w / 16) * 4;
                    int l = ((b[i] & 255) * 3 + (b[i + 1] & 255) * 6 + (b[i + 2] & 255)) / 10;
                    small[gy * 8 + gx] = (byte) l; sum += l;
                }
                float mot = 0;
                if (prevSmall != null) { long d = 0; for (int i = 0; i < 64; i++) d += Math.abs((small[i] & 255) - (prevSmall[i] & 255)); mot = Math.min(1f, d / (64f * 60f)); }
                prevSmall = small;
                Frame f = new Frame(startPosMs + n * 1000 / FPS, b, sum / (64f * 255f), mot, feat, avg);
                n++;
                // keep at most ~4 s of pictures ahead of the clock; the sound paces FFmpeg
                while (!closed && frames.remainingCapacity() == 0) Thread.sleep(5);
                frames.put(f);
                if (wallStartNanos < 0 && audio == null) { wallStartNanos = System.nanoTime(); wallBaseMs = startPosMs; }
            }
        } catch (Exception e) {
            if (!closed && error == null) error = e.toString();
        }
    }

    private void readAudio() {
        try (Socket s = audioServer.accept(); InputStream in = new BufferedInputStream(s.getInputStream(), 1 << 16)) {
            byte[] buf = new byte[AudioOut.CHUNK_FRAMES * 4];
            while (!closed) {
                int off = 0;
                while (off < buf.length) { int k = in.read(buf, off, buf.length - off); if (k < 0) break; off += k; }
                if (off <= 0) break;
                // loudness for 4D: overall RMS and a cheap low-pass (bass) RMS
                long sq = 0, bs = 0; int lp = 0, count = off / 4;
                for (int i = 0; i + 3 < off; i += 4) {
                    int l = (short) ((buf[i] & 255) | (buf[i + 1] << 8)), r = (short) ((buf[i + 2] & 255) | (buf[i + 3] << 8));
                    int m = (l + r) / 2;
                    sq += (long) m * m;
                    lp += (m - lp) / 24;                                    // ~300 Hz one-pole low-pass
                    bs += (long) lp * lp;
                }
                if (count > 0) { loud = (float) Math.min(1, Math.sqrt(sq / (double) count) / 9000); bass = (float) Math.min(1, Math.sqrt(bs / (double) count) / 6000); }
                var lv = audioLevels.feed(buf, 0, off & ~3);
                synchronized (levelLog) {
                    levelLog.addLast(new Object[]{startPosMs + audioFrames * 1000L / AudioOut.RATE, lv});
                    while (levelLog.size() > 400) levelLog.removeFirst();
                }
                audioFrames += (off & ~3) / 4;
                if (audio != null && !audio.write(buf, off & ~3)) break;
                if (off < buf.length) break;
            }
        } catch (Exception e) {
            if (!closed) EarthLifeMod.LOG.info("cinema sound stream ended: {}", e.toString());
        }
        if (audio == null) return;                  // analysis only: the wall clock already paces the picture
        audioEnded = true;
        // the sound ran out (or failed): keep the picture going on the wall clock
        wallBaseMs = clockMs();
        wallStartNanos = System.nanoTime();
    }

    /** Film position now (ms). */
    public long clockMs() {
        if (audio != null && audio.ok && !audioEnded) return audio.started() ? startPosMs + audio.playedMs() : startPosMs;
        if (wallStartNanos < 0) return startPosMs;
        return wallBaseMs + (System.nanoTime() - wallStartNanos) / 1_000_000;
    }

    /** Render thread: shows the newest frame that is due and uploads it. */
    public void updateTexture() {
        if (closed || w == 0) return;
        long now = clockMs();
        Frame due = null;
        while (true) {
            Frame f = frames.peek();
            if (f == null || f.ptsMs > now + 15) break;
            frames.poll();
            if (due != null) recycle(due.rgba);
            due = f;
        }
        if (due == null) return;
        if (texture == null) {
            texture = new DynamicTexture(() -> "EarthLife cinema " + key, w, h, false);
            textureId = EarthLifeMod.id("cinema/" + key.replace(':', '_') + "_" + Integer.toHexString(System.identityHashCode(this)));
            Minecraft.getInstance().getTextureManager().register(textureId, texture);
        }
        NativeImage img = texture.getPixels();
        if (img != null) {
            MemoryUtil.memByteBuffer(img.getPointer(), w * h * 4).put(0, due.rgba, 0, w * h * 4);
            texture.upload();
        }
        if (shown != null) recycle(shown.rgba);
        shown = due;
        luma = due.luma; motion = due.motion;
        long wall = System.currentTimeMillis();
        if (input != null && input.jellyfinServer() != null) {
            if (progressStarted == 0) { progressStarted = wall; lastProgressMs = wall; MediaLibrary.jellyfinProgress(input, "start", due.ptsMs, false); }
            else if (wall - lastProgressMs > 30_000) { lastProgressMs = wall; MediaLibrary.jellyfinProgress(input, "progress", due.ptsMs, false); }
        }
        features = due.features; avgRgb = due.avgRgb;
        shownLevels = levelsAt(due.ptsMs);
        synchronized (pendingFx) {
            while (!pendingFx.isEmpty() && pendingFx.peekFirst().ptsMs() <= due.ptsMs + 20) {
                var ev = pendingFx.removeFirst();
                if (analyse4dx) { fxEvents.add(ev); if (fxEvents.size() > 64) fxEvents.poll(); }
            }
        }
        if (screenX && sidesDirty) uploadSides();
        status = "Playing";
    }

    /** Mean colour of a frame (sampled), for the light the screen throws into the room. */
    int average(byte[] b) {
        long r = 0, g = 0, bl = 0; int c = 0;
        for (int y = h / 8; y < h; y += h / 4) for (int x = w / 16; x < w; x += w / 8) { int i = (y * w + x) * 4; r += b[i] & 255; g += b[i + 1] & 255; bl += b[i + 2] & 255; c++; }
        return c == 0 ? 0 : (int) (r / c) << 16 | (int) (g / c) << 8 | (int) (bl / c);
    }

    /**
     * ScreenX side walls: there is no real side footage in an ordinary film, so the walls get an atmospheric extension
     * of the picture's own edges - the outer strip mirrored outwards, blurred more and darker the further it goes.
     * It carries the scene's colour and movement into the side vision; it does not invent scenery.
     */
    void sides(byte[] b) {
        byte[] l = new byte[SIDE_W * SIDE_H * 4], r = new byte[SIDE_W * SIDE_H * 4];
        int strip = Math.max(4, w / 7);
        for (int y = 0; y < SIDE_H; y++) {
            int sy = Math.min(h - 1, y * h / SIDE_H);
            for (int x = 0; x < SIDE_W; x++) {
                float t = x / (float) (SIDE_W - 1);               // 0 = at the screen edge, 1 = far end of the wall
                int reach = Math.max(1, (int) (strip * (0.15f + 0.85f * t)));
                int blur = 1 + (int) (t * 6);
                for (int side = 0; side < 2; side++) {
                    long rr = 0, gg = 0, bb = 0; int cnt = 0;
                    for (int k = -blur; k <= blur; k += Math.max(1, blur / 2)) {
                        int sx = side == 0 ? Math.min(w - 1, Math.max(0, reach + k)) : Math.max(0, Math.min(w - 1, w - 1 - reach - k));
                        int yy = Math.min(h - 1, Math.max(0, sy + k * 2));
                        int i = (yy * w + sx) * 4; rr += b[i] & 255; gg += b[i + 1] & 255; bb += b[i + 2] & 255; cnt++;
                    }
                    float fade = 0.85f - 0.6f * t;
                    byte[] dst = side == 0 ? l : r;
                    int o = (y * SIDE_W + x) * 4;
                    dst[o] = (byte) (rr / cnt * fade); dst[o + 1] = (byte) (gg / cnt * fade); dst[o + 2] = (byte) (bb / cnt * fade); dst[o + 3] = (byte) 255;
                }
            }
        }
        sideL = l; sideR = r; sidesDirty = true;
    }

    private void uploadSides() {
        sidesDirty = false;
        byte[] l = sideL, r = sideR;
        if (l == null) return;
        if (texL == null) {
            texL = new DynamicTexture(() -> "ZMF ScreenX left " + key, SIDE_W, SIDE_H, false);
            texR = new DynamicTexture(() -> "ZMF ScreenX right " + key, SIDE_W, SIDE_H, false);
            String base = "cinema/" + key.replace(':', '_') + "_" + Integer.toHexString(System.identityHashCode(this));
            idL = EarthLifeMod.id(base + "_sx_l"); idR = EarthLifeMod.id(base + "_sx_r");
            Minecraft.getInstance().getTextureManager().register(idL, texL);
            Minecraft.getInstance().getTextureManager().register(idR, texR);
        }
        for (int s = 0; s < 2; s++) {
            DynamicTexture t = s == 0 ? texL : texR;
            NativeImage img = t.getPixels();
            if (img == null) continue;
            MemoryUtil.memByteBuffer(img.getPointer(), SIDE_W * SIDE_H * 4).put(0, s == 0 ? l : r, 0, SIDE_W * SIDE_H * 4);
            t.upload();
        }
    }

    void gain(float g) { gain = g; if (audio != null) audio.gain(g * (float) CinemaConfig.volume); }

    public boolean finished() { return "The End".equals(status) && frames.isEmpty(); }

    @Override public void close() {
        if (!closed && progressStarted > 0 && input != null) MediaLibrary.jellyfinProgress(input, "stop", shown == null ? startPosMs : shown.ptsMs, false);
        closed = true;
        if (proc != null) proc.destroyForcibly();
        try { if (audioServer != null) audioServer.close(); } catch (IOException ignored) {}
        if (audio != null) audio.close();
        frames.clear();
        for (Identifier id : new Identifier[]{textureId, idL, idR}) {
            if (id == null) continue;
            Minecraft.getInstance().execute(() -> Minecraft.getInstance().getTextureManager().release(id));
        }
    }
}
