package earthlife.mod.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.*;

/** Server -> client packets. UNVERIFIED: Identifier may be renamed (Identifier) in 26.x. */
public final class Payloads {
    private Payloads() {}

    static Identifier id(String p) { return Identifier.fromNamespaceAndPath("earthlife", p); }

    /** Live aircraft state for the seatback screen. ETA is already in real seconds (time compression applied). */
    public record FlightTelemetry(String flight, String from, String to, String phase, double lat, double lon, double altitudeM,
                                  double groundSpeed, double heading, double remainingM, double etaSeconds,
                                  double[] routeLat, double[] routeLon) implements CustomPacketPayload {
        public static final Type<FlightTelemetry> TYPE = new Type<>(id("flight_telemetry"));
        public static final StreamCodec<FriendlyByteBuf, FlightTelemetry> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeUtf(p.flight); buf.writeUtf(p.from); buf.writeUtf(p.to); buf.writeUtf(p.phase);
            buf.writeDouble(p.lat); buf.writeDouble(p.lon); buf.writeDouble(p.altitudeM); buf.writeDouble(p.groundSpeed);
            buf.writeDouble(p.heading); buf.writeDouble(p.remainingM); buf.writeDouble(p.etaSeconds);
            buf.writeVarInt(p.routeLat.length);
            for (int i = 0; i < p.routeLat.length; i++) { buf.writeDouble(p.routeLat[i]); buf.writeDouble(p.routeLon[i]); }
        }, buf -> {
            String f = buf.readUtf(), a = buf.readUtf(), b = buf.readUtf(), ph = buf.readUtf();
            double lat = buf.readDouble(), lon = buf.readDouble(), alt = buf.readDouble(), gs = buf.readDouble(), hdg = buf.readDouble(), rem = buf.readDouble(), eta = buf.readDouble();
            int n = Math.min(buf.readVarInt(), 512);
            double[] rl = new double[n], ro = new double[n];
            for (int i = 0; i < n; i++) { rl[i] = buf.readDouble(); ro[i] = buf.readDouble(); }
            return new FlightTelemetry(f, a, b, ph, lat, lon, alt, gs, hdg, rem, eta, rl, ro);
        });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Wallet + reward countdown for the HUD and wallet screen. */
    public record WalletStatus(long balance, long ticksUntilHourly, long hourlyReady, boolean dailyReady) implements CustomPacketPayload {
        public static final Type<WalletStatus> TYPE = new Type<>(id("wallet_status"));
        public static final StreamCodec<FriendlyByteBuf, WalletStatus> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarLong(p.balance); buf.writeVarLong(p.ticksUntilHourly); buf.writeVarLong(p.hourlyReady); buf.writeBoolean(p.dailyReady);
        }, buf -> new WalletStatus(buf.readVarLong(), buf.readVarLong(), buf.readVarLong(), buf.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Positions of everyone inside an aircraft cabin: {playerEntityId, seated(0/1), x*1000, z*1000}. */
    public record CabinState(int aircraftId, List<int[]> walkers) implements CustomPacketPayload {
        public static final Type<CabinState> TYPE = new Type<>(id("cabin_state"));
        public static final StreamCodec<FriendlyByteBuf, CabinState> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.aircraftId); buf.writeVarInt(p.walkers.size());
            for (int[] w : p.walkers) { buf.writeVarInt(w[0]); buf.writeBoolean(w[1] == 1); buf.writeInt(w[2]); buf.writeInt(w[3]); }
        }, buf -> {
            int id = buf.readVarInt(), n = Math.min(buf.readVarInt(), 256);
            List<int[]> l = new ArrayList<>();
            for (int i = 0; i < n; i++) l.add(new int[]{buf.readVarInt(), buf.readBoolean() ? 1 : 0, buf.readInt(), buf.readInt()});
            return new CabinState(id, l);
        });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** One-line HUD info: clock mode/time and current job shift. */
    public record HudStatus(String clock, String job) implements CustomPacketPayload {
        public static final Type<HudStatus> TYPE = new Type<>(id("hud_status"));
        public static final StreamCodec<FriendlyByteBuf, HudStatus> CODEC = StreamCodec.of((buf, p) -> { buf.writeUtf(p.clock); buf.writeUtf(p.job); },
                buf -> new HudStatus(buf.readUtf(), buf.readUtf()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ---------------------------------------------------------------- V0.4

    static void writeStrings(FriendlyByteBuf buf, List<String> l) { buf.writeVarInt(l.size()); for (String x : l) buf.writeUtf(x); }
    static List<String> readStrings(FriendlyByteBuf buf, int max) {
        int n = Math.min(buf.readVarInt(), max); List<String> l = new ArrayList<>(n);
        for (int i = 0; i < n; i++) l.add(buf.readUtf()); return l;
    }

    /** A named point on the map: town, airport, landmark, event, NPC or waypoint. kind is a short tag ("town", "airport"...). */
    public record Poi(String kind, String name, double x, double z) {
        static void write(FriendlyByteBuf b, Poi p) { b.writeUtf(p.kind); b.writeUtf(p.name); b.writeDouble(p.x); b.writeDouble(p.z); }
        static Poi read(FriendlyByteBuf b) { return new Poi(b.readUtf(), b.readUtf(), b.readDouble(), b.readDouble()); }
    }
    static void writePois(FriendlyByteBuf b, List<Poi> l) { b.writeVarInt(l.size()); for (Poi p : l) Poi.write(b, p); }
    static List<Poi> readPois(FriendlyByteBuf b, int max) {
        int n = Math.min(b.readVarInt(), max); List<Poi> l = new ArrayList<>(n);
        for (int i = 0; i < n; i++) l.add(Poi.read(b)); return l;
    }

    /**
     * Sent on join (and after reloads): whether this is an Earth world and how to draw its map. The client draws roads
     * and buildings from the same bundled map data the server uses, positioned with this origin.
     */
    public record WorldInfo(boolean earth, String region, double originLat, double originLon, int seaLevelYAt0m, String startTown,
                            String scenario, boolean bundledData, String attribution, boolean showPlayersAllowed, boolean welcome,
                            String settingsSummary, List<Poi> pois) implements CustomPacketPayload {
        public static final Type<WorldInfo> TYPE = new Type<>(id("world_info"));
        public static final StreamCodec<FriendlyByteBuf, WorldInfo> CODEC = StreamCodec.of((b, p) -> {
            b.writeBoolean(p.earth); b.writeUtf(p.region); b.writeDouble(p.originLat); b.writeDouble(p.originLon); b.writeVarInt(p.seaLevelYAt0m);
            b.writeUtf(p.startTown); b.writeUtf(p.scenario); b.writeBoolean(p.bundledData); b.writeUtf(p.attribution); b.writeBoolean(p.showPlayersAllowed);
            b.writeBoolean(p.welcome); b.writeUtf(p.settingsSummary); writePois(b, p.pois);
        }, b -> new WorldInfo(b.readBoolean(), b.readUtf(), b.readDouble(), b.readDouble(), b.readVarInt(), b.readUtf(), b.readUtf(), b.readBoolean(),
                b.readUtf(), b.readBoolean(), b.readBoolean(), b.readUtf(), readPois(b, 4096)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Once a second: everything the HUD, LifeMap and EarthLife menu show about the player's own life. */
    public record LifeState(long balance, long ticksUntilHourly, boolean hourlyReady, boolean dailyReady, String clock, String job, boolean onShift,
                            String place, double lat, double lon, double elevationM, List<String> objectives, int objectivesDone,
                            List<Poi> waypoints, String eventLine, int candy, boolean halloween, boolean sharingLocation) implements CustomPacketPayload {
        public static final Type<LifeState> TYPE = new Type<>(id("life_state"));
        public static final StreamCodec<FriendlyByteBuf, LifeState> CODEC = StreamCodec.of((b, p) -> {
            b.writeVarLong(p.balance); b.writeVarLong(p.ticksUntilHourly); b.writeBoolean(p.hourlyReady); b.writeBoolean(p.dailyReady);
            b.writeUtf(p.clock); b.writeUtf(p.job); b.writeBoolean(p.onShift); b.writeUtf(p.place); b.writeDouble(p.lat); b.writeDouble(p.lon); b.writeDouble(p.elevationM);
            writeStrings(b, p.objectives); b.writeVarInt(p.objectivesDone); writePois(b, p.waypoints); b.writeUtf(p.eventLine);
            b.writeVarInt(p.candy); b.writeBoolean(p.halloween); b.writeBoolean(p.sharingLocation);
        }, b -> new LifeState(b.readVarLong(), b.readVarLong(), b.readBoolean(), b.readBoolean(), b.readUtf(), b.readUtf(), b.readBoolean(), b.readUtf(),
                b.readDouble(), b.readDouble(), b.readDouble(), readStrings(b, 64), b.readVarInt(), readPois(b, 256), b.readUtf(), b.readVarInt(), b.readBoolean(), b.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** One row of a menu list: a button runs {@code command} (an /earthlife command, so the server decides everything). */
    public record Row(String title, String detail, String button, String command) {}

    /** A list for an EarthLife menu tab (jobs, nearby, events, vehicles, flights). */
    public record UiList(String tab, String title, List<Row> rows) implements CustomPacketPayload {
        public static final Type<UiList> TYPE = new Type<>(id("ui_list"));
        public static final StreamCodec<FriendlyByteBuf, UiList> CODEC = StreamCodec.of((b, p) -> {
            b.writeUtf(p.tab); b.writeUtf(p.title); b.writeVarInt(p.rows.size());
            for (Row r : p.rows) { b.writeUtf(r.title); b.writeUtf(r.detail); b.writeUtf(r.button); b.writeUtf(r.command); }
        }, b -> {
            String tab = b.readUtf(), title = b.readUtf(); int n = Math.min(b.readVarInt(), 200);
            List<Row> rows = new ArrayList<>(n);
            for (int i = 0; i < n; i++) rows.add(new Row(b.readUtf(), b.readUtf(), b.readUtf(), b.readUtf()));
            return new UiList(tab, title, rows);
        });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** A resident speaks; each option, when chosen, runs "/earthlife talk <entity> <index>". */
    public record Dialogue(int entityId, String npcName, String relation, String line, List<String> options) implements CustomPacketPayload {
        public static final Type<Dialogue> TYPE = new Type<>(id("dialogue"));
        public static final StreamCodec<FriendlyByteBuf, Dialogue> CODEC = StreamCodec.of((b, p) -> {
            b.writeVarInt(p.entityId); b.writeUtf(p.npcName); b.writeUtf(p.relation); b.writeUtf(p.line); writeStrings(b, p.options);
        }, b -> new Dialogue(b.readVarInt(), b.readUtf(), b.readUtf(), b.readUtf(), readStrings(b, 8)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Positions of players who chose to share their location (and only when the server allows it). */
    public record MapPlayers(List<Poi> players) implements CustomPacketPayload {
        public static final Type<MapPlayers> TYPE = new Type<>(id("map_players"));
        public static final StreamCodec<FriendlyByteBuf, MapPlayers> CODEC = StreamCodec.of((b, p) -> writePois(b, p.players), b -> new MapPlayers(readPois(b, 256)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Ask the client to open one of its screens ("map", "menu:jobs", ...). Sent by /earthlife map and friends. */
    public record OpenScreen(String screen) implements CustomPacketPayload {
        public static final Type<OpenScreen> TYPE = new Type<>(id("open_screen"));
        public static final StreamCodec<FriendlyByteBuf, OpenScreen> CODEC = StreamCodec.of((b, p) -> b.writeUtf(p.screen), b -> new OpenScreen(b.readUtf()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
