package earthlife.mod.zmf;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.*;

/** ZMF Cinema packets. Film sources are references ("jellyfin:server:item", "file:...", "web:url"), never access tokens. */
public final class ZmfPayloads {
    private ZmfPayloads() {}

    static Identifier rid(String p) { return Identifier.fromNamespaceAndPath("earthlife", p); }

    /** One room on a showtime board. status: playing | trailers | next | soon (Coming Soon) | web. Times are server epoch ms. */
    public record RoomInfo(String key, int number, String name, String formats, String browser, String title, int year, String genre,
                           String source, String status, long filmStartMs, long endMs, long nextStartMs) {
        static void write(FriendlyByteBuf b, RoomInfo r) {
            b.writeUtf(r.key); b.writeVarInt(r.number); b.writeUtf(r.name); b.writeUtf(r.formats); b.writeUtf(r.browser); b.writeUtf(r.title);
            b.writeVarInt(r.year); b.writeUtf(r.genre); b.writeUtf(r.source, 4096); b.writeUtf(r.status); b.writeLong(r.filmStartMs); b.writeLong(r.endMs); b.writeLong(r.nextStartMs);
        }
        static RoomInfo read(FriendlyByteBuf b) {
            return new RoomInfo(b.readUtf(), b.readVarInt(), b.readUtf(), b.readUtf(), b.readUtf(), b.readUtf(), b.readVarInt(), b.readUtf(), b.readUtf(4096),
                    b.readUtf(), b.readLong(), b.readLong(), b.readLong());
        }
    }

    /** The program of one multiplex (sent to players near it). */
    public record Schedule(String id, String name, String style, int roomCount, long serverNowMs, List<RoomInfo> rooms) implements CustomPacketPayload {
        public static final Type<Schedule> TYPE = new Type<>(rid("zmf_schedule"));
        public static final StreamCodec<FriendlyByteBuf, Schedule> CODEC = StreamCodec.of((b, p) -> {
            b.writeUtf(p.id); b.writeUtf(p.name); b.writeUtf(p.style); b.writeVarInt(p.roomCount); b.writeLong(p.serverNowMs);
            b.writeVarInt(p.rooms.size()); for (RoomInfo r : p.rooms) RoomInfo.write(b, r);
        }, b -> {
            String id = b.readUtf(), name = b.readUtf(), style = b.readUtf(); int rc = b.readVarInt(); long now = b.readLong();
            int n = Math.min(b.readVarInt(), 64); List<RoomInfo> l = new ArrayList<>(n);
            for (int i = 0; i < n; i++) l.add(RoomInfo.read(b));
            return new Schedule(id, name, style, rc, now, l);
        });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** For the Cinema Locator: the nearest multiplex (built or planned). */
    public record Nearest(String name, double x, double z, boolean built, int rooms, boolean found) implements CustomPacketPayload {
        public static final Type<Nearest> TYPE = new Type<>(rid("zmf_nearest"));
        public static final StreamCodec<FriendlyByteBuf, Nearest> CODEC = StreamCodec.of((b, p) -> {
            b.writeUtf(p.name); b.writeDouble(p.x); b.writeDouble(p.z); b.writeBoolean(p.built); b.writeVarInt(p.rooms); b.writeBoolean(p.found);
        }, b -> new Nearest(b.readUtf(), b.readDouble(), b.readDouble(), b.readBoolean(), b.readVarInt(), b.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** A film title a player can play (shared so the scheduler can put it on screens). */
    public record CatalogEntry(String source, String title, int year, String genre, long runtimeMs, String format3d) {}

    /** Client -> server: titles of the player's library (Jellyfin and configured folders). first = replaces their earlier list. */
    public record Catalog(boolean first, boolean last, List<CatalogEntry> entries) implements CustomPacketPayload {
        public static final Type<Catalog> TYPE = new Type<>(rid("zmf_catalog"));
        public static final StreamCodec<FriendlyByteBuf, Catalog> CODEC = StreamCodec.of((b, p) -> {
            b.writeBoolean(p.first); b.writeBoolean(p.last); b.writeVarInt(p.entries.size());
            for (var e : p.entries) { b.writeUtf(e.source, 4096); b.writeUtf(e.title); b.writeVarInt(e.year); b.writeUtf(e.genre); b.writeVarLong(e.runtimeMs); b.writeUtf(e.format3d); }
        }, b -> {
            boolean first = b.readBoolean(), last = b.readBoolean(); int n = Math.min(b.readVarInt(), 2000);
            List<CatalogEntry> l = new ArrayList<>(n);
            for (int i = 0; i < n; i++) l.add(new CatalogEntry(b.readUtf(4096), b.readUtf(), b.readVarInt(), b.readUtf(), b.readVarLong(), b.readUtf()));
            return new Catalog(first, last, l);
        });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
