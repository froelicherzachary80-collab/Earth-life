package earthlife.mod.client.cinema;

import earthlife.mod.EarthLifeMod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * bHaptics TactSuit (e.g. TactSuit Air) support for the 4D auditoriums, through the bHaptics Player app running on the
 * same PC (local WebSocket API on port 15881, "v2/feedbacks"). EarthLife sends short dot frames for the front and back
 * of the vest (20 points each, which the Player maps onto the suit's motors). Nothing is sent anywhere else; if the
 * Player isn't running, EarthLife retries now and then and the 4D effects work without the suit.
 */
public final class Haptics {
    private Haptics() {}

    private static volatile WebSocket socket;
    private static volatile long nextTry;
    private static volatile CompletableFuture<WebSocket> connecting;
    public static volatile String status = "TactSuit: not connected";
    private static long lastSend;

    static String url() {
        return String.format(Locale.ROOT, "ws://%s:%d/v2/feedbacks?app_id=earthlife.minecraft&app_name=EarthLife", CinemaConfig.hapticsHost, CinemaConfig.hapticsPort);
    }

    public static boolean connected() { return socket != null && !socket.isOutputClosed(); }

    static void ensure() {
        if (!CinemaConfig.haptics || connected() || (connecting != null && !connecting.isDone()) || System.currentTimeMillis() < nextTry) return;
        nextTry = System.currentTimeMillis() + 30_000;
        status = "TactSuit: connecting to bHaptics Player...";
        connecting = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().newWebSocketBuilder()
                .buildAsync(URI.create(url()), new WebSocket.Listener() {
                    @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) { ws.request(1); return null; }
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

    /**
     * Plays one frame: front/back are 20 intensities (0..100) in 4 columns x 5 rows, top row first.
     * Rate-limited to 10 frames per second.
     */
    public static void frame(int[] front, int[] back, int durationMs) {
        ensure();
        WebSocket ws = socket;
        long now = System.currentTimeMillis();
        if (ws == null || now - lastSend < 95) return;
        lastSend = now;
        String msg = "{\"Register\":[],\"Submit\":[" + submit("earthlife_front", "VestFront", front, durationMs) + "," + submit("earthlife_back", "VestBack", back, durationMs) + "]}";
        try { ws.sendText(msg, true); } catch (Exception e) { socket = null; }
    }

    static String submit(String key, String pos, int[] v, int dur) {
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < v.length; i++) if (v[i] > 0) {
            if (!dots.isEmpty()) dots.append(',');
            dots.append("{\"Index\":").append(i).append(",\"Intensity\":").append(Math.max(0, Math.min(100, v[i]))).append('}');
        }
        return "{\"Type\":\"frame\",\"Key\":\"" + key + "\",\"Frame\":{\"Position\":\"" + pos + "\",\"PathPoints\":[],\"DotPoints\":[" + dots + "],\"DurationMillis\":" + dur + "}}";
    }

    public static void close() {
        WebSocket ws = socket;
        socket = null;
        if (ws != null) try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye"); } catch (Exception ignored) {}
    }
}
