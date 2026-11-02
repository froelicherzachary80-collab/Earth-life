package zmf.cinema.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * In-game tests of the standalone ZMF Cinema mod in a real Minecraft 26.3 client (GitHub Actions:
 * `gradle :zmf-cinema-mod:runClientGameTest`). Results: zmfcinema-test-report.json, screenshots in screenshots/.
 * A check is only PASSED if the condition was observed in the running game; missing hardware/services are BLOCKED.
 */
public final class ZmfStandaloneGameTest implements FabricClientGameTest {
    final Map<String, Map<String, Object>> report = new LinkedHashMap<>();
    final List<String> log = new ArrayList<>();
    Path runDir;

    void record(String test, String check, String status, String evidence) {
        report.computeIfAbsent(test, k -> new LinkedHashMap<>()).put(check, status + " - " + evidence);
        String line = String.format("[%s] %s: %s - %s", test, check, status, evidence);
        log.add(line);
        System.out.println("ZMFCINEMA-TEST " + line);
        save();
    }

    void check(String test, String check, boolean ok, String evidence) { record(test, check, ok ? "PASSED" : "FAILED", evidence); }

    void attempt(String test, String check, Runnable r) {
        try { r.run(); } catch (Throwable t) { record(test, check, "FAILED", "exception: " + t); t.printStackTrace(); }
    }

    /** Turns the camera towards an auditorium's screen centre. */
    void lookAtScreen(ClientGameTestContext ctx, String key) {
        double[] sc = ctx.computeOnClient(mc -> {
            for (var e : mc.level.entitiesForRendering()) if (e instanceof zmf.cinema.block.CinemaScreenEntity s && s.key().equals(key))
                return new double[]{s.getX(), s.getY() + s.screenHeight() / 2, s.getZ()};
            return null;
        });
        if (sc == null) return;
        float[] yp = ctx.computeOnClient(mc -> {
            double dx = sc[0] - mc.player.getX(), dy = sc[1] - mc.player.getEyeY(), dz = sc[2] - mc.player.getZ();
            return new float[]{(float) Math.toDegrees(Math.atan2(-dx, dz)), (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)))};
        });
        ctx.getInput().lookAt(yp[0], yp[1]);
        ctx.waitTicks(5);
    }

    void save() {
        try {
            StringBuilder sb = new StringBuilder("{\n");
            int i = 0;
            for (var e : report.entrySet()) {
                sb.append("  \"").append(esc(e.getKey())).append("\": {\n");
                int j = 0;
                for (var c : e.getValue().entrySet())
                    sb.append("    \"").append(esc(c.getKey())).append("\": \"").append(esc(String.valueOf(c.getValue()))).append("\"").append(++j < e.getValue().size() ? ",\n" : "\n");
                sb.append("  }").append(++i < report.size() ? ",\n" : "\n");
            }
            sb.append("}\n");
            Files.writeString(runDir.resolve("zmfcinema-test-report.json"), sb.toString());
            Files.write(runDir.resolve("zmfcinema-test-log.txt"), log);
        } catch (Exception ignored) {}
    }

    static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " "); }

    @Override public void runTest(ClientGameTestContext ctx) {
        runDir = ctx.computeOnClient(mc -> mc.gameDirectory.toPath());
        ctx.waitTicks(40);
        attempt("ZMF Cinema", "mod loaded", () -> {
            var fl = net.fabricmc.loader.api.FabricLoader.getInstance();
            check("ZMF Cinema", "mod loaded", fl.isModLoaded("zmfcinema") && !fl.isModLoaded("earthlife"),
                    "zmfcinema " + fl.getModContainer("zmfcinema").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?") + " loaded on Minecraft "
                            + fl.getModContainer("minecraft").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?") + " without EarthLife (earthlife loaded: " + fl.isModLoaded("earthlife") + ")");
        });
        new ZmfCinemaGameTest(this).run(ctx);
        save();
        long failed = report.values().stream().flatMap(m -> m.values().stream()).filter(v -> v.toString().startsWith("FAILED")).count();
        System.out.println("ZMFCINEMA-TEST SUMMARY: " + failed + " failed checks");
        if (failed > 0) throw new AssertionError(failed + " ZMF Cinema in-game checks failed - see zmfcinema-test-report.json");
    }
}
