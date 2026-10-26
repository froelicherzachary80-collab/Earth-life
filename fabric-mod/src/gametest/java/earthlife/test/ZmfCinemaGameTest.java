package earthlife.test;

import earthlife.core.cinema.FormatAllocator;
import earthlife.mod.cinema.CinemaContent;
import earthlife.mod.cinema.CinemaService;
import earthlife.mod.client.cinema.*;
import earthlife.mod.client.zmf.LibraryShare;
import earthlife.mod.client.zmf.ZmfState;
import earthlife.mod.zmf.MultiplexRecord;
import earthlife.mod.zmf.MultiplexService;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;

/**
 * ZMF Cinema v0.1 acceptance tests (Test 17), run in a real Minecraft 26.3 client in an ordinary (non-Earth) world.
 * Each check records PASSED / FAILED / BLOCKED with the evidence it looked at. Hardware and services that don't exist on
 * a CI runner (a Jellyfin server, a TactSuit, a GPU) are reported as BLOCKED or tested against a stand-in, never as
 * passed.
 */
final class ZmfCinemaGameTest {
    final EarthLifeClientGameTest t;
    static final String T = "Test 17 - ZMF Cinema";
    final Map<Integer, String> built = new LinkedHashMap<>();        // rooms -> multiplex id
    Path films;

    ZmfCinemaGameTest(EarthLifeClientGameTest t) { this.t = t; }

    <R> R server(TestSingleplayerContext sp, Function<MinecraftServer, R> f) { return sp.getServer().computeOnServer(f::apply); }

    /** Polls a server-side condition once a second (from the test thread) for up to `seconds`. */
    boolean until(ClientGameTestContext ctx, TestSingleplayerContext sp, Function<MinecraftServer, Boolean> cond, int seconds) {
        for (int i = 0; i < seconds; i++) { if (server(sp, cond)) return true; ctx.waitTicks(20); }
        return server(sp, cond);
    }

    final Map<String, String> programs = new LinkedHashMap<>();

    String program(MultiplexService ms, MultiplexRecord rec) {
        long now = System.currentTimeMillis();
        StringBuilder prog = new StringBuilder();
        for (int i = 0; i < rec.rooms; i++) { var m = ms.movieFor(rec, i, now); prog.append(m == null ? "soon" : m.title()).append(','); }
        return prog.toString();
    }
    static ServerPlayer player(MinecraftServer s) { return s.getPlayerList().getPlayers().get(0); }
    void check(String name, String r) { t.check(T, name, r.endsWith("|true"), r.substring(0, r.lastIndexOf('|'))); }

    void run(ClientGameTestContext ctx) {
        TestWorldSave save = null;
        try (TestSingleplayerContext sp = ctx.worldBuilder().adjustSettings(ui -> {
            ui.setName("ZMF Cinema Test World");
            ui.setAllowCommands(true);
        }).create()) {
            save = sp.getWorldSave();
            sp.getConnection().waitForChunksRender(false, 20 * 60 * 3);
            ctx.waitTicks(40);
            server(sp, s -> { earthlife.mod.McCompat.setDayTime(s, 6000); MultiplexService.get().pauseNatural = true; return 0; });
            t.attempt(T, "running in an ordinary world", () -> check("running in an ordinary world", server(sp, s -> {
                var ms = MultiplexService.get();
                return "generator=" + s.overworld().getChunkSource().getGenerator().getClass().getSimpleName() + ", Earth services=" + earthlife.mod.EarthLifeServices.running()
                        + ", ZMF service=" + (ms != null) + ", natural generation=" + (ms != null && ms.naturalEnabled()) + "|" + (ms != null && !earthlife.mod.EarthLifeServices.running());
            })));
            prepareFilms(ctx);
            int[] xs = {0, 4000, 8000};
            int k = 0;
            for (int n : new int[]{12, 20, 32}) { int x = xs[k++]; t.attempt(T, n + "-screen multiplex", () -> multiplex(ctx, sp, n, x)); }
            t.attempt(T, "formats differ between buildings", () -> check("formats differ between buildings", server(sp, s -> {
                Set<String> sigs = new HashSet<>();
                StringBuilder sb = new StringBuilder();
                for (var r : MultiplexService.get().all()) {
                    if (r.state != MultiplexRecord.State.BUILT) continue;
                    var p = MultiplexService.get().plan(r);
                    sb.append(r.name).append(": ").append(FormatAllocator.summary(p.formats())).append(", ").append(p.style().label).append("; ");
                    sigs.add(p.style() + FormatAllocator.summary(p.formats()));
                }
                return sb + "|" + (sigs.size() >= 2);
            })));
            t.attempt(T, "natural generation + Cinema Locator", () -> natural(ctx, sp));
            t.attempt(T, "Jellyfin server", () -> t.record(T, "Jellyfin server", "BLOCKED",
                    "no Jellyfin server on the CI runner - connection, login, library metadata and streaming need a real server (see STATUS.md for how to verify at home)"));
            t.attempt(T, "library shared + films assigned", () -> assignment(ctx, sp));
            t.attempt(T, "showtimes menu", () -> menus(ctx, sp));
            t.attempt(T, "auto 3D from a 2D film", () -> auto3d(ctx, sp));
            t.attempt(T, "4DX event detection", () -> fourDx(ctx, sp));
            t.attempt(T, "IMAX + ScreenX rooms", () -> imaxScreenX(ctx, sp));
            t.attempt(T, "only the active room decodes", () -> streams(ctx, sp));
            t.attempt(T, "web cinema (MCEF Modern)", () -> browser(ctx, sp));
            t.attempt(T, "TactSuit protocol (mock bHaptics Player)", () -> haptics(ctx));
            server(sp, s -> {
                player(s).stopRiding();
                var ms = MultiplexService.get();
                for (var id : built.values()) programs.put(id, program(ms, ms.record(id)));
                ms.save(); return 0;
            });
        } catch (Throwable e) { t.record(T, "ZMF world", "FAILED", "exception: " + e); e.printStackTrace(); }
        if (save != null) persistence(ctx, save);
    }

    // ---------------------------------------------------------------- test films (synthetic, made by FFmpeg on the runner)

    void prepareFilms(ClientGameTestContext ctx) {
        films = Path.of("test-films").toAbsolutePath();
        Path lib = films.resolve("library");
        try {
            Files.createDirectories(lib);
            Path base = films.resolve("test-pattern.mp4");
            String[] names = {"Test Pattern Comedy (2012).mp4", "Test Pattern Space (2024).mp4", "Test Pattern Action (2018).mp4", "Test Pattern Night (2007).mp4",
                    "Test Pattern Fantasy (2026).mp4", "Old Test Reel (1999).mp4"};
            if (Files.exists(base)) for (String n : names) if (!Files.exists(lib.resolve(n))) Files.copy(base, lib.resolve(n));
            Path ex = films.resolve("explosion-test.mp4");
            if (Files.exists(ex) && !Files.exists(lib.resolve("Explosion Test (2020).mp4"))) Files.copy(ex, lib.resolve("Explosion Test (2020).mp4"));
            ctx.runOnClient(mc -> { CinemaConfig.libraryFolders.clear(); CinemaConfig.libraryFolders.add(lib.toString()); CinemaConfig.shareLibrary = true; });
        } catch (Exception e) { t.record(T, "test films", "BLOCKED", "could not prepare test films: " + e); }
    }

    // ---------------------------------------------------------------- generation

    void multiplex(ClientGameTestContext ctx, TestSingleplayerContext sp, int rooms, int x) {
        String name = rooms + "-screen multiplex";
        server(sp, s -> { ServerPlayer p = player(s); p.stopRiding(); p.teleportTo(s.overworld(), x + 0.5, 120, 0.5, Set.of(), 180, 0, false);
            p.getAbilities().mayfly = true; p.getAbilities().flying = true; p.onUpdateAbilities(); return 0; });
        ctx.waitTicks(60);
        String id = server(sp, s -> {
            ServerPlayer p = player(s);
            int y = s.overworld().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, 0);
            p.teleportTo(s.overworld(), x + 0.5, y + 1, 0.5, Set.of(), 180, 0, false);
            String size = rooms == 12 ? "small" : rooms == 20 ? "large" : "mega";
            s.getCommands().performPrefixedCommand(p.createCommandSourceStack(), "zmfcinema generate " + size);
            var r = MultiplexService.get().all().stream().filter(m -> !m.natural && m.rooms == rooms).reduce((a, b) -> b).orElse(null);
            return r == null ? null : r.id;
        });
        if (id == null) { t.record(T, name, "FAILED", "/zmfcinema generate did not create a multiplex"); return; }
        long t0 = System.currentTimeMillis();
        until(ctx, sp, s -> MultiplexService.get().record(id).state == MultiplexRecord.State.BUILT, 240);
        built.put(rooms, id);
        String r = server(sp, s -> {
            var ms = MultiplexService.get();
            var rec = ms.record(id);
            var plan = ms.plan(rec);
            var lvl = s.overworld();
            var cs = CinemaService.get();
            Set<String> keys = new HashSet<>();
            int seatsOk = 0, seatsAll = 0, roomsOk = 0, doorsOpen = 0, screens = 0;
            List<String> bad = new ArrayList<>();
            for (var room : plan.rooms()) {
                var ref = ms.room(rec.id + "-" + room.index());
                if (ref == null) { bad.add("room " + room.index() + " not registered"); continue; }
                keys.add(ref.aud().key());
                int ok = 0;
                for (BlockPos sp2 : ref.aud().seats()) { seatsAll++; if (lvl.getBlockState(sp2).getBlock() instanceof earthlife.mod.cinema.SeatBlock) { ok++; seatsOk++; } }
                double[] d = ref.aud().door();
                BlockPos door = BlockPos.containing(d[0] + ref.aud().nx() * 1.0, d[1], d[2] + ref.aud().nz() * 1.0);
                boolean open = lvl.getBlockState(door).isAir() || lvl.getBlockState(door.above()).isAir();
                if (open) doorsOpen++;
                // the screen wall behind the screen centre is black concrete
                BlockPos wall = BlockPos.containing(ref.aud().sx() - ref.aud().nx() * 0.6, ref.aud().sy(), ref.aud().sz() - ref.aud().nz() * 0.6);
                if (lvl.getBlockState(wall).is(Blocks.BLACK_CONCRETE)) screens++;
                if (ok == ref.aud().seats().size() && ok > 0) roomsOk++; else bad.add(room.name() + " seats " + ok + "/" + ref.aud().seats().size());
            }
            var errs = FormatAllocator.validate(plan.formats());
            String ev = String.format(Locale.ROOT, "%s (%s, %s) built in %.0f s: %d of %d rooms complete, %d unique room ids, %d/%d seat blocks in place, %d doors open, %d screen walls; %s; allocation %s%s",
                    rec.name, plan.style().label, plan.floors() + (plan.floors() == 1 ? " floor" : " floors"), (System.currentTimeMillis() - t0) / 1000.0, roomsOk, rooms, keys.size(), seatsOk, seatsAll, doorsOpen, screens,
                    FormatAllocator.summary(plan.formats()), errs.isEmpty() ? "valid" : "INVALID " + errs, bad.isEmpty() ? "" : "; problems: " + String.join(", ", bad.subList(0, Math.min(5, bad.size()))));
            return ev + "|" + (rec.state == MultiplexRecord.State.BUILT && roomsOk == rooms && keys.size() == rooms && errs.isEmpty() && doorsOpen >= rooms - 1
                    && cs.cinemas.stream().anyMatch(c -> c.id().equals("zmf:" + id)));
        });
        check(name, r);
        // screenshots: outside (facade), lobby
        server(sp, s -> {
            var ms = MultiplexService.get(); var rec = ms.record(id); var pl = ms.placed(rec);
            double[] out = pl.toWorld(-26, 0);
            player(s).teleportTo(s.overworld(), out[0], rec.y + 9, out[1], Set.of(), (float) (pl.headingDeg + 180) % 360 - 180, 12, false);
            return 0;
        });
        float yawOut = yawTowards(sp, id, 10);
        ctx.runOnClient(mc -> { mc.player.setYRot(yawOut); mc.player.setXRot(10); });
        ctx.waitTicks(80);
        ctx.takeScreenshot("50_zmf_" + rooms + "_exterior");
        server(sp, s -> {
            var ms = MultiplexService.get(); var rec = ms.record(id); var pl = ms.placed(rec);
            double[] in = pl.toWorld(3, 0);
            player(s).teleportTo(s.overworld(), in[0], rec.y + 1, in[1], Set.of(), 0, 0, false);
            return 0;
        });
        float yawIn = yawTowards(sp, id, 30);
        ctx.runOnClient(mc -> { mc.player.setYRot(yawIn); mc.player.setXRot(5); });
        ctx.waitTicks(60);
        ctx.takeScreenshot("51_zmf_" + rooms + "_lobby");
    }

    /** Yaw (MC convention) from the player towards a point `u` blocks into the building on its axis. */
    float yawTowards(TestSingleplayerContext sp, String id, double u) {
        return server(sp, s -> {
            var ms = MultiplexService.get(); var rec = ms.record(id); var pl = ms.placed(rec);
            double[] w = pl.toWorld(u, 0);
            ServerPlayer p = player(s);
            return (float) Math.toDegrees(Math.atan2(-(w[0] - p.getX()), w[1] - p.getZ()));
        });
    }

    // ---------------------------------------------------------------- natural generation + locator

    void natural(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        String r = server(sp, s -> {
            var ms = MultiplexService.get();
            ms.pauseNatural = false;
            ServerPlayer p = player(s);
            p.stopRiding();
            p.teleportTo(s.overworld(), 20000.5, 150, 20000.5, Set.of(), 0, 0, false);
            var rec = ms.nearest(p.getX(), p.getZ(), true);
            if (rec == null) return "no suitable natural site found near 20000, 20000|false";
            ms.forceBuild(rec);
            p.getInventory().clearContent();
            p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(CinemaContent.CINEMA_LOCATOR));
            return rec.id + "|true";
        });
        if (!r.endsWith("|true")) { check("naturally generated multiplex", r); return; }
        String id = r.substring(0, r.indexOf('|'));
        until(ctx, sp, s -> MultiplexService.get().record(id).state == MultiplexRecord.State.BUILT, 240);
        // stand 40 blocks in front of its doors (so it is certainly the nearest), holding the locator
        server(sp, s -> {
            var ms = MultiplexService.get(); var rec = ms.record(id); var pl = ms.placed(rec);
            double[] w = pl.toWorld(-40, 0);
            player(s).teleportTo(s.overworld(), w[0], rec.y + 3, w[1], Set.of(), 0, 0, false);
            return 0;
        });
        ctx.waitTicks(40);
        String loc = ctx.computeOnClient(mc -> {
            var n = ZmfState.nearest;
            return n == null ? "locator: no answer" : String.format(Locale.ROOT, "locator shows %s at %.0f, %.0f (built=%s, found=%s), %.0f blocks from the player",
                    n.name(), n.x(), n.z(), n.built(), n.found(), Math.hypot(n.x() - mc.player.getX(), n.z() - mc.player.getZ()));
        });
        String sr = server(sp, s -> {
            var ms = MultiplexService.get(); var rec = ms.record(id);
            String txt = ms.locateText(player(s));
            return String.format(Locale.ROOT, "natural site %s (%s, %d screens) at %d %d %d, state %s; /zmfcinema locate nearest: \"%s\"", rec.id, rec.name, rec.rooms, rec.x, rec.y, rec.z, rec.state, txt)
                    + "|" + (rec.natural && rec.state == MultiplexRecord.State.BUILT && txt.contains(rec.name));
        });
        boolean locOk = ctx.computeOnClient(mc -> ZmfState.nearest != null && ZmfState.nearest.found() && ZmfState.nearest.built());
        t.check(T, "naturally generated multiplex", sr.endsWith("|true"), sr.substring(0, sr.lastIndexOf('|')));
        t.check(T, "Cinema Locator points to it", locOk, loc);
        ctx.takeScreenshot("52_zmf_locator_hud");
        // the map with a marker
        String map = server(sp, s -> {
            ServerPlayer p = player(s);
            MultiplexService.get().giveMap(p);
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(net.minecraft.world.item.Items.FILLED_MAP)) return "filled map with a target marker given|true";
            return "no map in the inventory|false";
        });
        check("map marker", map);
        server(sp, s -> { MultiplexService.get().pauseNatural = true; return 0; });
    }

    // ---------------------------------------------------------------- catalog + assignment

    void assignment(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        if (films == null || !Files.isDirectory(films.resolve("library"))) { t.record(T, "library shared + films assigned", "BLOCKED", "no test films"); return; }
        String[] st = {""};
        ctx.runOnClient(mc -> LibraryShare.shareNow(s -> st[0] = s));
        for (int i = 0; i < 60 && st[0].isEmpty(); i++) ctx.waitTicks(20);
        ctx.waitTicks(40);
        String id = built.get(12);
        String r = server(sp, s -> {
            var ms = MultiplexService.get();
            var cat = ms.catalog();
            var rec = ms.record(id);
            long now = System.currentTimeMillis();
            List<String> titles = new ArrayList<>(); int soon = 0;
            Set<String> distinct = new HashSet<>();
            for (int i = 0; i < rec.rooms; i++) { var m = ms.movieFor(rec, i, now); if (m == null) soon++; else { titles.add(m.title() + " (" + m.year() + ")"); distinct.add(m.key()); } }
            boolean oldExcluded = cat.stream().noneMatch(m -> m.year() < 2000);
            return String.format("client: %s; catalog %d titles (2000+ only: %s); 12-screen program: %s, %d rooms Coming Soon", st[0], cat.size(), oldExcluded, titles, soon)
                    + "|" + (cat.size() >= 5 && oldExcluded && distinct.size() == titles.size() && titles.size() == Math.min(12, cat.size()) && soon == 12 - titles.size());
        });
        check("library shared + films assigned", r);
        String other = server(sp, s -> {
            var ms = MultiplexService.get(); long now = System.currentTimeMillis();
            var a = ms.record(built.get(12)); var b = ms.record(built.get(20));
            List<String> pa = new ArrayList<>(), pb = new ArrayList<>();
            for (int i = 0; i < 6; i++) { var m = ms.movieFor(a, i, now); pa.add(m == null ? "-" : m.title()); var n = ms.movieFor(b, i, now); pb.add(n == null ? "-" : n.title()); }
            return "first rooms: " + pa + " vs " + pb + "|" + !pa.equals(pb);
        });
        check("programs differ between multiplexes", other);
    }

    void menus(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        String id = built.get(12);
        server(sp, s -> {
            var ms = MultiplexService.get(); var rec = ms.record(id); var pl = ms.placed(rec);
            double[] in = pl.toWorld(4, 0);
            player(s).teleportTo(s.overworld(), in[0], rec.y + 1, in[1], Set.of(), 0, 0, false);
            s.getCommands().performPrefixedCommand(player(s).createCommandSourceStack(), "zmfcinema");
            return 0;
        });
        ctx.waitTicks(40);
        String r = ctx.computeOnClient(mc -> {
            var l = earthlife.mod.client.ClientState.lists.get("zmf:showtimes");
            return (mc.gui.screen() == null ? "no screen" : mc.gui.screen().getClass().getSimpleName()) + ", " + (l == null ? "no list" : l.rows().size() + " rows, title " + l.title())
                    + "|" + (mc.gui.screen() instanceof earthlife.mod.client.zmf.ZmfScreen && l != null && l.rows().size() >= 12);
        });
        ctx.takeScreenshot("53_zmf_showtimes");
        check("showtimes menu", r);
        server(sp, s -> { s.getCommands().performPrefixedCommand(player(s).createCommandSourceStack(), "zmfcinema admin"); return 0; });
        ctx.waitTicks(30);
        String a = ctx.computeOnClient(mc -> { var l = earthlife.mod.client.ClientState.lists.get("zmf:admin"); return (l == null ? "no admin list" : l.rows().size() + " admin rows") + "|" + (l != null && l.rows().size() >= 12); });
        ctx.takeScreenshot("54_zmf_admin");
        check("admin panel", a);
        ctx.runOnClient(mc -> mc.gui.setScreen(null));
        // ticket + door scan: survival player without a ticket is turned away, with a ticket allowed in
        String door = server(sp, s -> {
            var ms = MultiplexService.get(); var rec = ms.record(id); ServerPlayer p = player(s);
            p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            var ref = ms.room(rec.id + "-0");
            var cs = CinemaService.get();
            String film = "file:" + films.resolve("test-pattern.mp4");
            cs.showings.put(ref.aud().key(), new CinemaService.Showing("Door Test", film, "2D", System.currentTimeMillis(), 0, "test", null));
            double[] b = ref.aud().box();
            p.teleportTo(s.overworld(), (b[0] + b[3]) / 2, b[1] + 0.2, (b[2] + b[5]) / 2, Set.of(), 0, 0, false);
            return ref.aud().key();
        });
        ctx.waitTicks(30);
        String door2 = server(sp, s -> {
            var ms = MultiplexService.get(); ServerPlayer p = player(s); var ref = ms.room(door);
            boolean turnedAway = !ref.aud().inside(p.getX(), p.getY(), p.getZ());
            p.getInventory().add(new ItemStack(net.minecraft.world.item.Items.EMERALD, 10));
            String bought;
            try { bought = ms.buyTicket(p, door); } catch (Exception e) { bought = "ticket failed: " + e.getMessage(); }
            double[] b = ref.aud().box();
            p.teleportTo(s.overworld(), (b[0] + b[3]) / 2, b[1] + 0.2, (b[2] + b[5]) / 2, Set.of(), 0, 0, false);
            return turnedAway + "|" + bought;
        });
        ctx.waitTicks(30);
        String door3 = server(sp, s -> {
            var ms = MultiplexService.get(); ServerPlayer p = player(s); var ref = ms.room(door);
            boolean inside = ref.aud().inside(p.getX(), p.getY(), p.getZ());
            int emeralds = 0; for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(net.minecraft.world.item.Items.EMERALD)) emeralds += p.getInventory().getItem(i).getCount();
            CinemaService.get().showings.remove(door);
            p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
            String[] a2 = door2.split("\\|", 2);
            return "without a ticket turned away: " + a2[0] + "; " + a2[1] + "; with the ticket inside: " + inside + "; emeralds left " + emeralds
                    + "|" + ("true".equals(a2[0]) && inside && emeralds < 10);
        });
        check("ticket bought and scanned at the door", door3);
    }

    // ---------------------------------------------------------------- playback: 3D, 4DX, IMAX, ScreenX

    String roomWith(TestSingleplayerContext sp, String mid, String fmt, boolean noBrowser) {
        return server(sp, s -> {
            var ms = MultiplexService.get(); var rec = ms.record(mid);
            for (var room : ms.plan(rec).rooms()) {
                var ref = ms.room(rec.id + "-" + room.index());
                if (ref.aud().has(fmt) && (!noBrowser || room.formats().browser() == '-')) return ref.aud().key();
            }
            return null;
        });
    }

    /** Starts a film in a room as a private showing and seats the player (looking at the screen). */
    void watch(ClientGameTestContext ctx, TestSingleplayerContext sp, String key, Path film, String format, boolean glasses) {
        server(sp, s -> {
            ServerPlayer p = player(s);
            p.stopRiding();
            p.setItemSlot(EquipmentSlot.HEAD, glasses ? new ItemStack(CinemaContent.GLASSES_3D) : ItemStack.EMPTY);
            var src = p.createCommandSourceStack();
            s.getCommands().performPrefixedCommand(src, "earthlife cinema start " + key + " " + format + " \"file:" + film.toString().replace("\\", "\\\\") + "\" " + film.getFileName());
            s.getCommands().performPrefixedCommand(src, "earthlife cinema sit " + key);
            return 0;
        });
        ctx.waitTicks(40);
        t.lookAtScreen(ctx, key);
    }

    void auto3d(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        Path film = films.resolve("test-pattern.mp4");
        if (!Files.exists(film)) { t.record(T, "auto 3D from a 2D film", "BLOCKED", "no test film"); return; }
        String key = roomWith(sp, built.get(20), "3D", true);
        if (key == null) { t.record(T, "auto 3D from a 2D film", "FAILED", "no 3D room in the 20-screen multiplex"); return; }
        watch(ctx, sp, key, film, "AUTO3D", true);
        ctx.waitTicks(200);
        String r = ctx.computeOnClient(mc -> {
            var p = CinemaClient.player(key);
            if (p == null) return "no video player for " + key + "|false";
            return String.format(Locale.ROOT, "room %s, source 2D, format %s, anaglyph=%s, auto3d=%s, status %s, %dx%d, conversion %.1f ms/frame, mean disparity %.2f px, depth estimator: %s",
                    key, p.format, p.anaglyph, p.auto3d, p.status, p.width(), p.height(), p.convertMs, p.disparityPx, p.depthModel)
                    + "|" + (p.auto3d && p.anaglyph && p.texture() != null && p.disparityPx > 0.2f);
        });
        ctx.takeScreenshot("55_zmf_auto3d_anaglyph");
        check("auto 3D from a 2D film", r);
    }

    void fourDx(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        Path film = films.resolve("explosion-test.mp4");
        if (!Files.exists(film)) { t.record(T, "4DX event detection", "BLOCKED", "no explosion test clip"); return; }
        String key = roomWith(sp, built.get(20), "4DX", true);
        if (key == null) { t.record(T, "4DX event detection", "FAILED", "no 4DX room"); return; }
        int before = ctx.computeOnClient(mc -> FourD.eventsTotal);
        ctx.runOnClient(mc -> { FourD.maxPitchSeen = 0; Atmosphere.emitted.clear(); });
        watch(ctx, sp, key, film, "2D", false);
        ctx.waitTicks(20 * 9);
        String r = ctx.computeOnClient(mc -> {
            var p = CinemaClient.player(key);
            boolean motion = mc.player.getVehicle() instanceof earthlife.mod.cinema.SeatEntity se && se.motion();
            return String.format(Locale.ROOT, "4DX room %s, motion seat=%s, film %s, events detected: %d (%s), max seat pitch %.1f°, atmosphere effects %s",
                    key, motion, p == null ? "not playing" : p.status, FourD.eventsTotal - before, FourD.recent, FourD.maxPitchSeen, Atmosphere.emitted)
                    + "|" + (motion && FourD.eventsTotal > before && FourD.recent.stream().anyMatch(e -> e.type() == earthlife.core.cinema.media.Auto4DXEngine.Type.EXPLOSION) && FourD.maxPitchSeen > 1f);
        });
        ctx.takeScreenshot("56_zmf_4dx");
        check("4DX event detection", r);
        boolean atmos = ctx.computeOnClient(mc -> Atmosphere.emitted.containsKey(earthlife.core.cinema.media.Auto4DXEngine.Type.EXPLOSION));
        t.check(T, "4DX atmospheric effect", atmos, "virtual effect emitted for the detected event: " + ctx.computeOnClient(mc -> Atmosphere.emitted.toString()));
    }

    void imaxScreenX(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        Path film = films.resolve("test-pattern.mp4");
        String imax = roomWith(sp, built.get(32), "IMAX", true), sx = roomWith(sp, built.get(32), "ScreenX", false);
        if (imax != null) {
            watch(ctx, sp, imax, film, "2D", false);
            ctx.waitTicks(160);
            ctx.takeScreenshot("57_zmf_imax");
            String r = ctx.computeOnClient(mc -> { var p = CinemaClient.player(imax); return "IMAX room " + imax + ": " + (p == null ? "not playing" : p.status + " " + p.width() + "x" + p.height()) + "|" + (p != null && p.texture() != null); });
            check("IMAX room plays", r);
        } else t.record(T, "IMAX room plays", "FAILED", "no IMAX room in the mega multiplex");
        if (sx != null) {
            watch(ctx, sp, sx, film, "2D", false);
            ctx.waitTicks(160);
            ctx.takeScreenshot("58_zmf_screenx");
            String r = ctx.computeOnClient(mc -> {
                var p = CinemaClient.player(sx);
                return "ScreenX room " + sx + ": " + (p == null ? "not playing" : p.status + ", side walls " + p.sideTexture(true) + " / " + p.sideTexture(false))
                        + "|" + (p != null && p.texture() != null && p.sideTexture(true) != null);
            });
            check("ScreenX side walls", r);
        } else t.record(T, "ScreenX side walls", "FAILED", "no ScreenX room");
    }

    void streams(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        String mid = built.get(32);
        Path film = films.resolve("test-pattern.mp4");
        // start films in 10 rooms, then stand in the hallway: nothing should decode; walk into one room: exactly one
        String hall = server(sp, s -> {
            var ms = MultiplexService.get(); var rec = ms.record(mid); ServerPlayer p = player(s);
            p.stopRiding();
            var cs = CinemaService.get();
            int n = 0;
            for (var room : ms.plan(rec).rooms()) {
                if (n >= 10) break;
                var key = rec.id + "-" + room.index();
                cs.showings.put(key, new CinemaService.Showing("Load Test " + n, "file:" + film, "2D", System.currentTimeMillis(), 0, "test", null));
                cs.broadcast(s, key); n++;
            }
            var pl = ms.placed(rec); var plan = ms.plan(rec);
            double[] hw = pl.toWorld(plan.rooms().get(0).u0() + 4, 0);
            p.teleportTo(s.overworld(), hw[0], rec.y + 1, hw[1], Set.of(), 0, 0, false);
            return "10 showings started";
        });
        ctx.waitTicks(80);
        int inHall = ctx.computeOnClient(mc -> CinemaClient.activeStreams);
        int shows = ctx.computeOnClient(mc -> CinemaClient.showCount());
        String key = server(sp, s -> {
            var ms = MultiplexService.get(); var rec = ms.record(mid); var ref = ms.room(rec.id + "-0");
            double[] b = ref.aud().box();
            player(s).teleportTo(s.overworld(), (b[0] + b[3]) / 2, b[1] + 0.2, (b[2] + b[5]) / 2, Set.of(), 0, 0, false);
            return ref.aud().key();
        });
        ctx.waitTicks(80);
        int inRoom = ctx.computeOnClient(mc -> CinemaClient.activeStreams);
        String active = ctx.computeOnClient(mc -> CinemaClient.activeKey);
        t.check(T, "only the active room decodes", inHall == 0 && inRoom == 1 && active.equals(key),
                String.format("%s; client knows %d showings; standing in the hallway: %d films decoding; inside room %s: %d decoding (%s)", hall, shows, inHall, key, inRoom, active));
        server(sp, s -> { CinemaService.get().showings.clear(); return 0; });
    }

    // ---------------------------------------------------------------- web cinema

    void browser(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        if (!BrowserScreens.available()) { t.record(T, "web cinema (MCEF Modern)", "BLOCKED", "MCEF Modern not installed in this test run (optional mod) - " + BrowserScreens.engineStatus()); return; }
        String key = server(sp, s -> {
            var ms = MultiplexService.get(); var rec = ms.record(built.get(12));
            for (var room : ms.plan(rec).rooms()) if (room.formats().browser() == 'A') {
                var ref = ms.room(rec.id + "-" + room.index());
                ms.setBrowser(ref, "https://example.com/");
                double[] b = ref.aud().box();
                player(s).teleportTo(s.overworld(), (b[0] + b[3]) / 2, b[1] + 3, (b[2] + b[5]) / 2, Set.of(), 0, 0, false);
                return ref.aud().key();
            }
            return null;
        });
        if (key == null) { t.record(T, "web cinema (MCEF Modern)", "FAILED", "no browser room A"); return; }
        for (int i = 0; i < 300; i++) {
            boolean done = ctx.computeOnClient(mc -> { var se = BrowserScreens.session(key); return (se != null && se.hasFrame) || BrowserScreens.engineStatus().startsWith("Chromium failed") || BrowserScreens.engineStatus().contains("not recognised"); });
            if (done) break;
            ctx.waitTicks(20);
        }
        t.lookAtScreen(ctx, key);
        ctx.waitTicks(40);
        ctx.takeScreenshot("59_zmf_web_cinema");
        String r = ctx.computeOnClient(mc -> {
            var se = BrowserScreens.session(key);
            return "room " + key + ": " + BrowserScreens.engineStatus() + (se == null ? ", no session" : ", frames " + se.frames + ", url " + BrowserScreens.url(se)) + "|" + (se != null && se.hasFrame);
        });
        String status = ctx.computeOnClient(mc -> BrowserScreens.engineStatus());
        if (!r.endsWith("|true") && (status.startsWith("Chromium failed") || status.startsWith("Chromium:")))
            t.record(T, "web cinema (MCEF Modern)", "BLOCKED", "Chromium could not start on the CI runner: " + r.substring(0, r.lastIndexOf('|')));
        else check("web cinema (MCEF Modern)", r);
        server(sp, s -> { var ms = MultiplexService.get(); ms.setBrowser(ms.room(key), null); return 0; });
    }

    // ---------------------------------------------------------------- TactSuit protocol against a stand-in for the bHaptics Player

    void haptics(ClientGameTestContext ctx) {
        List<String> got = Collections.synchronizedList(new ArrayList<>());
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();
            Thread th = new Thread(() -> mockPlayer(ss, got), "mock-bhaptics");
            th.setDaemon(true); th.start();
            ctx.runOnClient(mc -> { CinemaConfig.hapticsPort = port; CinemaConfig.haptics = true; CinemaConfig.hapticMax = 50; CinemaConfig.hapticIntensity = 1.0; Haptics.close(); FourD.stopped = false; });
            ctx.runOnClient(mc -> Haptics.pattern(Haptics.Pattern.TEST_ALL, 1f, 0));
            for (int i = 0; i < 10 && !ctx.computeOnClient(mc -> Haptics.connected()); i++) ctx.waitTicks(20);
            ctx.waitTicks(10);
            ctx.runOnClient(mc -> Haptics.pattern(Haptics.Pattern.EXPLOSION, 1f, 0.5f));
            ctx.waitTicks(40);
            int maxI = 0;
            for (String m : got) { var mm = java.util.regex.Pattern.compile("\"Intensity\":(\\d+)").matcher(m); while (mm.find()) maxI = Math.max(maxI, Integer.parseInt(mm.group(1))); }
            String status = ctx.computeOnClient(mc -> Haptics.status);
            boolean ok = !got.isEmpty() && got.get(0).contains("VestFront") && got.get(0).contains("VestBack") && maxI > 0 && maxI <= 50;
            t.check(T, "TactSuit protocol (mock bHaptics Player)", ok, String.format("stand-in Player on port %d received %d frames; status \"%s\"; strongest motor %d (safety limit 50); first: %s",
                    port, got.size(), status, maxI, got.isEmpty() ? "-" : got.get(0).substring(0, Math.min(160, got.get(0).length()))));
            ctx.runOnClient(mc -> { FourD.stopped = true; Haptics.emergencyStop(); });
            int n = got.size();
            ctx.runOnClient(mc -> Haptics.pattern(Haptics.Pattern.EXPLOSION, 1f, 0));
            ctx.waitTicks(20);
            t.check(T, "emergency stop silences the vest", got.size() <= n + 1, "frames after the stop: " + (got.size() - n) + " (one zero frame allowed)");
            ctx.runOnClient(mc -> { FourD.stopped = false; Haptics.close(); CinemaConfig.hapticsPort = 15881; CinemaConfig.hapticMax = 80; CinemaConfig.hapticIntensity = 0.8; });
            t.record(T, "real TactSuit Air", "BLOCKED", "no TactSuit or bHaptics Player on the CI runner - only the protocol was tested against a stand-in");
        } catch (Exception e) { t.record(T, "TactSuit protocol (mock bHaptics Player)", "FAILED", "exception: " + e); }
    }

    /** A minimal WebSocket server that speaks like the bHaptics Player: reports a vest, records text frames. */
    static void mockPlayer(ServerSocket ss, List<String> got) {
        try {
            ss.setSoTimeout(60_000);
            Socket s = ss.accept();
            var in = new BufferedInputStream(s.getInputStream()); var out = s.getOutputStream();
            StringBuilder hdr = new StringBuilder();
            while (!hdr.toString().endsWith("\r\n\r\n")) { int c = in.read(); if (c < 0) return; hdr.append((char) c); }
            var m = java.util.regex.Pattern.compile("(?i)Sec-WebSocket-Key: *(\\S+)").matcher(hdr);
            if (!m.find()) return;
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((m.group(1) + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            byte[] status = "{\"ConnectedPositions\":[\"VestFront\",\"VestBack\"]}".getBytes(StandardCharsets.UTF_8);
            out.write(0x81); out.write(status.length); out.write(status); out.flush();
            while (true) {
                int b0 = in.read(), b1 = in.read();
                if (b0 < 0 || b1 < 0) return;
                long len = b1 & 127;
                if (len == 126) len = (in.read() << 8) | in.read();
                else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | in.read(); }
                byte[] mask = new byte[4];
                if ((b1 & 128) != 0) for (int i = 0; i < 4; i++) mask[i] = (byte) in.read();
                byte[] data = in.readNBytes((int) len);
                for (int i = 0; i < data.length; i++) data[i] ^= mask[i % 4];
                if ((b0 & 15) == 8) return;
                if ((b0 & 15) == 1) got.add(new String(data, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- persistence

    void persistence(ClientGameTestContext ctx, TestWorldSave save) {
        Map<String, String> before = new LinkedHashMap<>();
        try (TestSingleplayerContext sp = save.open()) {
            sp.getConnection().waitForChunksRender(false, 20 * 60 * 3);
            ctx.waitTicks(40);
            String r = server(sp, s -> {
                var ms = MultiplexService.get();
                var cs = CinemaService.get();
                long now = System.currentTimeMillis();
                List<String> lines = new ArrayList<>();
                boolean ok = true;
                for (var e : built.entrySet()) {
                    var rec = ms.record(e.getValue());
                    if (rec == null || rec.state != MultiplexRecord.State.BUILT) { ok = false; lines.add(e.getValue() + " missing"); continue; }
                    long rooms = cs.auds.values().stream().filter(a -> a.key().startsWith(rec.id + "-")).count();
                    String prog = program(ms, rec);
                    boolean same = prog.equals(programs.get(rec.id));
                    lines.add(String.format("%s %s: %d rooms registered, formats %s, same film program as before the reload: %s", rec.id, rec.name, rooms, FormatAllocator.summary(ms.plan(rec).formats()), same));
                    ok &= rooms == rec.rooms && same;
                }
                return String.join("; ", lines) + "|" + ok;
            });
            check("rooms and programs kept after reload", r);
        } catch (Throwable e) { t.record(T, "rooms and programs kept after reload", "FAILED", "exception: " + e); e.printStackTrace(); }
    }
}
