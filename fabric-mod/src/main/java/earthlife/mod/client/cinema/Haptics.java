package earthlife.mod.client.cinema;

import earthlife.core.cinema.media.Auto4DXEngine;
import earthlife.mod.EarthLifeMod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * bHaptics TactSuit (e.g. TactSuit Air) support for the 4D/4DX auditoriums, through the official bHaptics Player app
 * running on the same PC (its local WebSocket API on port 15881, "v2/feedbacks"; Java has no bHaptics SDK, the
 * Player's local API is the supported route for other languages). EarthLife sends short dot frames for the front and
 * back of the vest (20 motors each, 4 columns x 5 rows); nothing is sent anywhere else.
 *
 * Safety: every value is scaled by the intensity setting and capped at the configured maximum; a duty-cycle limiter
 * halves the strength when the vest has been buzzing more than 60% of the last 10 seconds; the emergency stop (END
 * key) silences it at once. If the Player isn't running, EarthLife retries now and then and the 4D effects work
 * without the suit. The vest only vibrates - it can't make wind, water, heat or motion.
 */
public final class Haptics {
    private Haptics() {}

    public enum Pattern { EXPLOSION, IMPACT, THUNDER, HEARTBEAT, ENGINE, TURBULENCE, WAVE, SWEEP_UP, RAIN, SHOCKWAVE, TEST_FRONT, TEST_BACK, TEST_ALL }

    private static volatile WebSocket socket;
    private static volatile long nextTry;
    private static volatile CompletableFuture<WebSocket> connecting;
    public static volatile String status = "TactSuit: not connected";
    public static volatile String devices = "";
    public static volatile int framesSent;
    public static volatile String lastMessage = "";
    private static long lastSend;
    private static final Deque<long[]> duty = new ArrayDeque<>();          // {start ms, duration ms}
    private static final Deque<int[][]> queue = new ArrayDeque<>();         // pattern frames: {front[20], back[20], {durationMs}}

    static String url() {
        return String.format(Locale.ROOT, "ws://%s:%d/v2/feedbacks?app_id=earthlife.minecraft&app_name=EarthLife", CinemaConfig.hapticsHost, CinemaConfig.hapticsPort);
    }

    public static boolean connected() { return socket != null && !socket.isOutputClosed(); }

    static void ensure() {
        if (!CinemaConfig.haptics || connected() || (connecting != null && !connecting.isDone()) || System.currentTimeMillis() < nextTry) return;
        nextTry = System.currentTimeMillis() + 15_000;           // automatic reconnection every 15 s
        status = "TactSuit: connecting to bHaptics Player...";
        connecting = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().newWebSocketBuilder()
                .buildAsync(URI.create(url()), new WebSocket.Listener() {
                    final StringBuilder buf = new StringBuilder();
                    @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        buf.append(data);
                        if (last) { parseStatus(buf.toString()); buf.setLength(0); }
                        ws.request(1); return null;
                    }
                    @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) { socket = null; status = "TactSuit: bHaptics Player closed the connection"; return null; }
                    @Override public void onError(WebSocket ws, Throwable error) { socket = null; status = "TactSuit: " + error.getClass().getSimpleName(); }
                })
                .whenComplete((ws, e) -> {
                    if (e != null) { status = "TactSuit: bHaptics Player not running on this PC"; return; }
                    socket = ws;
                    status = "TactSuit: connected to bHaptics Player";
                    EarthLifeMod.LOG.info("bHaptics Player connected");
                });
    }

    /** The Player reports connected devices ("ConnectedPositions": ["VestFront", "VestBack", ...]). */
    static void parseStatus(String json) {
        try {
            var m = earthlife.core.util.Json.obj(earthlife.core.util.Json.parse(json));
            Object pos = m.get("ConnectedPositions");
            if (pos != null) {
                List<String> l = new ArrayList<>();
                for (Object o : earthlife.core.util.Json.arr(pos)) l.add(String.valueOf(o));
                devices = String.join(", ", l);
                boolean vest = l.stream().anyMatch(s -> s.startsWith("Vest"));
                status = vest ? "TactSuit: connected (" + devices + ")" : l.isEmpty() ? "TactSuit: Player running, no device paired" : "TactSuit: connected (" + devices + ", no vest)";
            }
        } catch (Exception ignored) {}
    }

    /** Scales by the intensity setting, caps at the safety maximum and applies the duty-cycle limiter. */
    static int[] safe(int[] v, long now) {
        double k = Math.max(0, Math.min(1, CinemaConfig.hapticIntensity));
        while (!duty.isEmpty() && now - duty.peekFirst()[0] > 10_000) duty.removeFirst();
        long busy = 0; for (long[] d : duty) busy += d[1];
        if (busy > 6000) k *= 0.5;
        int cap = Math.max(0, Math.min(100, CinemaConfig.hapticMax));
        int[] o = new int[v.length];
        for (int i = 0; i < v.length; i++) o[i] = (int) Math.min(cap, Math.round(Math.max(0, v[i]) * k));
        return o;
    }

    /**
     * Plays one frame: front/back are 20 intensities (0..100) in 4 columns x 5 rows, top row first.
     * Rate-limited to 10 frames per second.
     */
    public static void frame(int[] front, int[] back, int durationMs) {
        if (FourD.stopped || !CinemaConfig.haptics) return;
        ensure();
        WebSocket ws = socket;
        long now = System.currentTimeMillis();
        if (ws == null || now - lastSend < 95) return;
        lastSend = now;
        int[] f = safe(front, now), b = safe(back, now);
        boolean any = false; for (int x : f) any |= x > 0; for (int x : b) any |= x > 0;
        if (!any) return;
        duty.addLast(new long[]{now, durationMs});
        String msg = "{\"Register\":[],\"Submit\":[" + submit("earthlife_front", "VestFront", f, durationMs) + "," + submit("earthlife_back", "VestBack", b, durationMs) + "]}";
        try { ws.sendText(msg, true); framesSent++; lastMessage = msg; } catch (Exception e) { socket = null; }
    }

    static String submit(String key, String pos, int[] v, int dur) {
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < v.length; i++) if (v[i] > 0) {
            if (!dots.isEmpty()) dots.append(',');
            dots.append("{\"Index\":").append(i).append(",\"Intensity\":").append(Math.max(0, Math.min(100, v[i]))).append('}');
        }
        return "{\"Type\":\"frame\",\"Key\":\"" + key + "\",\"Frame\":{\"Position\":\"" + pos + "\",\"PathPoints\":[],\"DotPoints\":[" + dots + "],\"DurationMillis\":" + dur + "}}";
    }

    /** Continuous seat vibration on the lower torso. */
    public static void rumble(float v) {
        if (!queue.isEmpty()) { playQueued(); return; }
        int[] f = new int[20], b = new int[20];
        int x = Math.round(Math.min(1, v) * 60);
        for (int i = 12; i < 20; i++) { f[i] = x; b[i] = x; }
        frame(f, b, 110);
    }

    static void playQueued() {
        int[][] fr = queue.peekFirst();
        if (fr == null) return;
        long now = System.currentTimeMillis();
        if (now - lastSend < 95) return;
        queue.pollFirst();
        frame(fr[0], fr[1], fr[2][0]);
    }

    /** Called every client tick so queued pattern frames keep playing even when no seat is moving. */
    public static void tick() { if (!queue.isEmpty()) playQueued(); }

    /** A recognised 4DX event as a vest pattern; dirX -1..1 leans it to the left/right side. */
    public static void event(Auto4DXEngine.Event e) {
        Pattern p = switch (e.type()) {
            case EXPLOSION -> Pattern.EXPLOSION; case IMPACT -> Pattern.IMPACT; case THUNDER -> Pattern.THUNDER; case HORROR_SCARE -> Pattern.HEARTBEAT;
            case CAR_CHASE -> Pattern.ENGINE; case AIRCRAFT -> Pattern.TURBULENCE; case OCEAN -> Pattern.WAVE; case MAGIC -> Pattern.SWEEP_UP;
            case RAIN -> Pattern.RAIN; case SPACE_BATTLE -> Pattern.SHOCKWAVE; default -> null;
        };
        if (p != null) pattern(p, e.intensity(), e.dirX());
    }

    /** Queues a pattern (replacing one still waiting). */
    public static void pattern(Pattern p, float strength, float dirX) {
        if (FourD.stopped) return;
        queue.clear();
        int s = Math.round(Math.max(0.2f, Math.min(1, strength)) * 100);
        switch (p) {
            case EXPLOSION -> { add(all(s, dirX), all(s * 7 / 10, dirX), 140); add(all(s * 6 / 10, dirX), all(s * 5 / 10, dirX), 140); add(all(s * 3 / 10, 0), all(s * 3 / 10, 0), 160); }
            case IMPACT -> add(centre(s), new int[20], 100);
            case THUNDER -> { add(new int[20], rows(s * 7 / 10, 2, 4), 200); add(new int[20], rows(s * 5 / 10, 3, 4), 250); }
            case HEARTBEAT -> { add(dot(s, 5, 6), new int[20], 90); add(new int[20], new int[20], 120); add(dot(s * 8 / 10, 5, 6), new int[20], 90); }
            case ENGINE -> { for (int i = 0; i < 3; i++) add(rows(s * 5 / 10, 3, 4), rows(s * 5 / 10, 3, 4), 110); }
            case TURBULENCE -> { for (int i = 0; i < 4; i++) add(random(s * 6 / 10), random(s * 6 / 10), 100); }
            case WAVE -> { for (int r = 0; r < 5; r++) add(rows(s * 6 / 10, r, r), rows(s * 4 / 10, r, r), 140); }
            case SWEEP_UP -> { for (int r = 4; r >= 0; r--) add(rows(s * 6 / 10, r, r), new int[20], 100); }
            case RAIN -> add(random(s * 3 / 10), new int[20], 90);
            case SHOCKWAVE -> { add(rows(s, 2, 2), rows(s * 6 / 10, 2, 2), 100); add(rows(s * 7 / 10, 1, 3), rows(s * 5 / 10, 1, 3), 100); add(all(s * 4 / 10, 0), all(s * 4 / 10, 0), 120); }
            case TEST_FRONT -> { for (int r = 0; r < 5; r++) add(rows(60, r, r), new int[20], 150); }
            case TEST_BACK -> { for (int r = 0; r < 5; r++) add(new int[20], rows(60, r, r), 150); }
            case TEST_ALL -> add(all(60, 0), all(60, 0), 300);
        }
        playQueued();
    }

    static void add(int[] f, int[] b, int ms) { queue.addLast(new int[][]{f, b, {ms}}); }
    static int[] all(int v, float dirX) {
        int[] a = new int[20];
        for (int i = 0; i < 20; i++) { int col = i % 4; float side = (col - 1.5f) / 1.5f; a[i] = Math.round(v * (1 + 0.4f * dirX * side)); }
        return a;
    }
    static int[] rows(int v, int r0, int r1) { int[] a = new int[20]; for (int r = r0; r <= r1; r++) for (int c = 0; c < 4; c++) a[r * 4 + c] = v; return a; }
    static int[] centre(int v) { int[] a = new int[20]; for (int i : new int[]{5, 6, 9, 10}) a[i] = v; return a; }
    static int[] dot(int v, int... idx) { int[] a = new int[20]; for (int i : idx) a[i] = v; return a; }
    static int[] random(int v) { int[] a = new int[20]; var r = new Random(); for (int i = 0; i < 5; i++) a[r.nextInt(20)] = v; return a; }

    /** Emergency stop: silence the vest now (a zero frame) and drop everything queued. */
    public static void emergencyStop() {
        queue.clear();
        WebSocket ws = socket;
        if (ws == null) return;
        String msg = "{\"Register\":[],\"Submit\":[" + submit("earthlife_front", "VestFront", new int[20], 1) + "," + submit("earthlife_back", "VestBack", new int[20], 1) + "]}";
        try { ws.sendText(msg, true); } catch (Exception ignored) {}
    }

    public static void close() {
        WebSocket ws = socket;
        socket = null;
        queue.clear();
        nextTry = 0; connecting = null;                 // settings changed: reconnect right away next time
        if (ws != null) try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye"); } catch (Exception ignored) {}
    }
}
