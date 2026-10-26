package earthlife.mod.client;

import earthlife.core.world.Features;
import earthlife.mod.net.Payloads;

import java.util.*;

/** Latest state received from the server (all writes on the client thread). */
public final class ClientState {
    private ClientState() {}

    public static volatile Payloads.WorldInfo world;
    public static volatile Payloads.LifeState life;
    public static volatile List<Payloads.Poi> sharedPlayers = List.of();
    public static final Map<String, Payloads.UiList> lists = new HashMap<>();
    public static volatile long lifeAtMs;

    /** Active navigation route (drawn on the map and as a HUD arrow). */
    public static volatile List<Features.Pt> route = List.of();
    public static volatile String routeLabel = "";
    public static volatile double[] routeTarget;

    public static boolean earth() { return world != null && world.earth(); }

    public static void reset() {
        world = null; life = null; sharedPlayers = List.of(); lists.clear(); route = List.of(); routeLabel = ""; routeTarget = null;
    }
}
