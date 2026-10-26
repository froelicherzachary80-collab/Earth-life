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

    record Frame(long ptsMs, byte[] rgba, float luma, float motion) {}

    final String key;
    final String source, format;
    final boolean anaglyph;
    public volatile String status = "Connecting...";
    public volatile String error;
    public volatile long durationMs;
    public volatile float aspect = 2.2f;
    public volatile boolean hasAudio;
    /** Live analysis for the 4D effects: loudness of the bass (0..1), loudness overall, picture brightness and motion. */
    public volatile float bass, loud, luma, motion;

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

    VideoPlayer(String key, String source, String format, boolean anaglyph) {
        this.key = key; this.source = source; this.format = format; this.anaglyph = anaglyph;
    }

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
        h = Math.max(144, Math.min(1080, CinemaConfig.quality)) & ~1;
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
            if (audio.ok) {
                audioServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                audioServer.setReceiveBufferSize(64 * 1024);
                audioServer.setSoTimeout(30_000);
                cmd.addAll(List.of("-map", "0:a:0", "-ac", "2", "-ar", String.valueOf(AudioOut.RATE), "-f", "s16le", "tcp://127.0.0.1:" + audioServer.getLocalPort()));
            } else {
                audio.close(); audio = null;
                cmd.add(cmd.indexOf("-i"), "-re");                                // no sound device: pace by the wall clock
            }
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
        if (audio != null) {
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
                Frame f = new Frame(startPosMs + n * 1000 / FPS, b, sum / (64f * 255f), mot);
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
                if (!audio.write(buf, off & ~3)) break;
                if (off < buf.length) break;
            }
        } catch (Exception e) {
            if (!closed) EarthLifeMod.LOG.info("cinema sound stream ended: {}", e.toString());
        }
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
        status = "Playing";
    }

    void gain(float g) { gain = g; if (audio != null) audio.gain(g * (float) CinemaConfig.volume); }

    public boolean finished() { return "The End".equals(status) && frames.isEmpty(); }

    @Override public void close() {
        closed = true;
        if (proc != null) proc.destroyForcibly();
        try { if (audioServer != null) audioServer.close(); } catch (IOException ignored) {}
        if (audio != null) audio.close();
        frames.clear();
        if (textureId != null) {
            var id = textureId;
            Minecraft.getInstance().execute(() -> Minecraft.getInstance().getTextureManager().release(id));
        }
    }
}
