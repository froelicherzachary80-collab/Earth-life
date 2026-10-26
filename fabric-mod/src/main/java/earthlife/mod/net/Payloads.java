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
}
