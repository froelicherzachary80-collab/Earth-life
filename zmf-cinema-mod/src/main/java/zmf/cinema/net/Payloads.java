// STANDALONE
package zmf.cinema.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.*;

/** ZMF Cinema packets (server -> client). */
public final class Payloads {
    private Payloads() {}

    static Identifier rid(String p) { return Identifier.fromNamespaceAndPath("zmfcinema", p); }

    /** One row of a menu list: the button runs {@code command} (a server command, so the server decides everything). */
    public record Row(String title, String detail, String button, String command) {}

    public record UiList(String tab, String title, List<Row> rows) implements CustomPacketPayload {
        public static final Type<UiList> TYPE = new Type<>(rid("ui_list"));
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

    /** Ask the client to open one of its screens ("zmf:showtimes", "cinema:pick:..."). */
    public record OpenScreen(String screen) implements CustomPacketPayload {
        public static final Type<OpenScreen> TYPE = new Type<>(rid("open_screen"));
        public static final StreamCodec<FriendlyByteBuf, OpenScreen> CODEC = StreamCodec.of((b, p) -> b.writeUtf(p.screen), b -> new OpenScreen(b.readUtf()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * A showing in an auditorium (empty title = nothing playing). positionMs is the film position when the server sent
     * this, so every client starts at the same moment. source: "jellyfin:<server>:<item>", "file:<path>", "url:<link>",
     * "ia:<identifier>" or "web:<url>"; never an access token. format: 2D, AUTO3D, SBS, HSBS, TAB, HTAB.
     */
    public record CinemaShow(String key, String title, String source, String format, long positionMs, boolean paused, boolean motion, String owner) implements CustomPacketPayload {
        public static final Type<CinemaShow> TYPE = new Type<>(rid("cinema_show"));
        public static final StreamCodec<FriendlyByteBuf, CinemaShow> CODEC = StreamCodec.of((b, p) -> {
            b.writeUtf(p.key); b.writeUtf(p.title); b.writeUtf(p.source, 4096); b.writeUtf(p.format); b.writeVarLong(p.positionMs); b.writeBoolean(p.paused); b.writeBoolean(p.motion); b.writeUtf(p.owner);
        }, b -> new CinemaShow(b.readUtf(), b.readUtf(), b.readUtf(4096), b.readUtf(), b.readVarLong(), b.readBoolean(), b.readBoolean(), b.readUtf()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
