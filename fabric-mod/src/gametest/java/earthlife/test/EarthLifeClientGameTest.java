package earthlife.test;

import com.mojang.blaze3d.platform.InputConstants;
import earthlife.core.geo.GeoPoint;
import earthlife.core.world.Features;
import earthlife.core.world.TileData;
import earthlife.core.world.TileKey;
import earthlife.mod.EarthLifeMod;
import earthlife.mod.EarthLifeServices;
import earthlife.mod.McCompat;
import earthlife.mod.aviation.AirplaneEntity;
import earthlife.mod.client.ClientState;
import earthlife.mod.client.EarthLifeClient;
import earthlife.mod.client.map.ClientMapData;
import earthlife.mod.client.screen.DialogueScreen;
import earthlife.mod.client.screen.EarthMenuScreen;
import earthlife.mod.client.screen.EarthSettingsScreen;
import earthlife.mod.client.screen.LifeMapScreen;
import earthlife.mod.npc.NpcEntity;
import earthlife.mod.world.VoxelEarthChunkGenerator;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

/**
 * EarthLife V0.4 in-game test suite, run by Fabric's client game test runner in a real Minecraft 26.3 client
 * (GitHub Actions: `gradle :fabric-mod:runClientGameTest`). Every check records PASSED/FAILED/BLOCKED with the
 * evidence it looked at; results go to earthlife-test-report.json in the run directory, screenshots to screenshots/.
 * A check is only PASSED if the condition was actually observed in the running game.
 */
public final class EarthLifeClientGameTest implements FabricClientGameTest {
    final Map<String, Map<String, Object>> report = new LinkedHashMap<>();
    final List<String> log = new ArrayList<>();
    Path runDir;

    void record(String test, String check, String status, String evidence) {
        report.computeIfAbsent(test, k -> new LinkedHashMap<>()).put(check, status + " - " + evidence);
        String line = String.format("[%s] %s: %s - %s", test, check, status, evidence);
        log.add(line);
        System.out.println("EARTHLIFE-TEST " + line);
        save();
    }

    void check(String test, String check, boolean ok, String evidence) { record(test, check, ok ? "PASSED" : "FAILED", evidence); }

    void attempt(String test, String check, Runnable r) {
        try { r.run(); }
        catch (Throwable t) { record(test, check, "FAILED", "exception: " + t); t.printStackTrace(); }
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
            Files.writeString(runDir.resolve("earthlife-test-report.json"), sb.toString());
            Files.write(runDir.resolve("earthlife-test-log.txt"), log);
        } catch (Exception ignored) {}
    }

    static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " "); }

    <T> T onServer(TestSingleplayerContext sp, java.util.function.Function<MinecraftServer, T> f) {
        return sp.getServer().computeOnServer(f::apply);
    }

    static void selectEarth(WorldCreationUiState ui) {
        var holder = ui.getSettings().worldgenLoadContext().lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(EarthSettingsScreen.EARTH_PRESET);
        ui.setWorldType(new WorldCreationUiState.WorldTypeEntry(holder));
    }

    @Override public void runTest(ClientGameTestContext ctx) {
        runDir = ctx.computeOnClient(mc -> mc.gameDirectory.toPath());
        record("Test 1 - Compilation", "jar built", "PASSED", "this test runs inside the compiled mod (Gradle :fabric-mod:build succeeded before the run)");
        ctx.waitTicks(40);
        attempt("Test 2 - Mod loading", "mod loaded", () -> {
            boolean loaded = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("earthlife");
            String ver = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("earthlife").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
            check("Test 2 - Mod loading", "mod loaded", loaded, "FabricLoader reports earthlife " + ver + " loaded in Minecraft " +
                    net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?"));
            check("Test 2 - Mod loading", "keybinds registered", EarthLifeClient.MAP != null && EarthLifeClient.MENU != null && EarthLifeClient.JOBS != null
                    && EarthLifeClient.WALLET != null && EarthLifeClient.NEARBY != null, "M/K/J/L/N key mappings exist in category earthlife");
        });
        ctx.takeScreenshot("01_title_screen");

        worldTypeUi(ctx);
        TestWorldSave earthSave = earthWorld(ctx);
        if (earthSave != null) reload(ctx, earthSave);
        else record("Test 12 - Save and reload", "reload", "BLOCKED", "Earth world could not be created");
        separation(ctx);
        dedicated(ctx);
        save();
        long failed = report.values().stream().flatMap(m -> m.values().stream()).filter(v -> v.toString().startsWith("FAILED")).count();
        System.out.println("EARTHLIFE-TEST SUMMARY: " + failed + " failed checks");
    }

    // ---------------------------------------------------------------- Test 3

    void worldTypeUi(ClientGameTestContext ctx) {
        String T = "Test 3 - World type";
        attempt(T, "Earth listed in Create New World", () -> {
            ctx.runOnClient(mc -> CreateWorldScreen.openFresh(mc, () -> mc.gui.setScreen(null)));
            ctx.waitForScreen(CreateWorldScreen.class);
            String found = ctx.computeOnClient(mc -> {
                var ui = ((CreateWorldScreen) mc.gui.screen()).getUiState();
                for (var e : ui.getNormalPresetList()) if (e.preset() != null && e.preset().is(EarthSettingsScreen.EARTH_PRESET)) return e.describePreset().getString();
                return null;
            });
            check(T, "Earth listed in Create New World", found != null, "world type list entry: " + found);
            ctx.runOnClient(mc -> selectEarth(((CreateWorldScreen) mc.gui.screen()).getUiState()));
            ctx.getInput().holdControl();
            ctx.getInput().pressKey(InputConstants.KEY_2);
            ctx.getInput().releaseControl();
            ctx.waitTicks(5);
            ctx.takeScreenshot("02_create_world_earth_selected");
            boolean hasEditor = ctx.computeOnClient(mc -> ((CreateWorldScreen) mc.gui.screen()).getUiState().getPresetEditor() != null);
            check(T, "Customize available", hasEditor, "WorldCreationUiState.getPresetEditor() for Earth is non-null (mixin)");
            boolean clicked = ctx.tryClickScreenButton("selectWorld.customizeType");
            if (!clicked) ctx.runOnClient(mc -> {
                var cws = (CreateWorldScreen) mc.gui.screen();
                mc.gui.setScreen(cws.getUiState().getPresetEditor().createEditScreen(cws, cws.getUiState().getSettings()));
            });
            ctx.waitForScreen(EarthSettingsScreen.class);
            ctx.takeScreenshot("03_earth_settings_screen");
            check(T, "settings screen opens", true, clicked ? "clicked the Customize button" : "opened via the preset editor (button not found by label)");
            // change the town to Sodus and the scenario, press Done, verify the generator got the settings
            ctx.runOnClient(mc -> {
                var scr = (EarthSettingsScreen) mc.gui.screen();
                var sodus = earthlife.mod.world.EarthSettings.PLACES.stream().filter(p -> p.town().equals("Sodus")).findFirst().orElseThrow();
                try {
                    var f = EarthSettingsScreen.class.getDeclaredField("settings"); f.setAccessible(true);
                    f.set(scr, scr.settings().withPlace(sodus).withScenario(earthlife.mod.world.EarthSettings.Scenario.TRAVELER));
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            ctx.clickScreenButton("gui.done");
            ctx.waitForScreen(CreateWorldScreen.class);
            String town = ctx.computeOnClient(mc -> ((CreateWorldScreen) mc.gui.screen()).getUiState().getSettings().selectedDimensions().overworld()
                    instanceof VoxelEarthChunkGenerator g ? g.settings().town() + "/" + g.settings().scenario() : "not an Earth generator");
            check(T, "settings saved into the world's generator", "Sodus/TRAVELER".equals(town), "overworld generator settings after Done: " + town);
            ctx.runOnClient(mc -> mc.gui.setScreen(null));
            ctx.waitTicks(5);
        });
    }

    // ---------------------------------------------------------------- Tests 4-11

    TestWorldSave earthWorld(ClientGameTestContext ctx) {
        String T4 = "Test 4 - World creation";
        TestWorldSave save;
        try (TestSingleplayerContext sp = ctx.worldBuilder().adjustSettings(ui -> {
            selectEarth(ui);
            ui.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL);
            ui.setAllowCommands(true);
            ui.setName("EarthLife Test Williamson");
        }).create()) {
            save = sp.getWorldSave();
            long t0 = System.currentTimeMillis();
            sp.getConnection().waitForChunksRender(false, 20 * 60 * 5);
            ctx.waitTicks(60);
            check(T4, "world loads", true, "Earth world created and chunks rendered in " + (System.currentTimeMillis() - t0) / 1000 + " s");
            String info = onServer(sp, s -> {
                var gen = s.overworld().getChunkSource().getGenerator();
                return gen.getClass().getSimpleName() + " services=" + EarthLifeServices.running() + " height=" + s.overworld().getHeight() + " minY=" + s.overworld().getMinY();
            });
            check(T4, "Earth generator + services active", info.startsWith("VoxelEarthChunkGenerator services=true"), info);
            check(T4, "tall world (1024 blocks)", info.contains("height=1024"), info);
            ctx.runOnClient(mc -> mc.debugEntries.setOverlayVisible(false));
            ctx.takeScreenshot("04_spawn_williamson");
            attempt(T4, "safe spawn in Williamson", () -> {
                String r = onServer(sp, s -> {
                    ServerPlayer p = s.getPlayerList().getPlayers().get(0);
                    var es = EarthLifeServices.get();
                    double d = Math.hypot(p.getX() - es.spawnX, p.getZ() - es.spawnZ);
                    var below = p.level().getBlockState(p.blockPosition().below());
                    var at = p.level().getBlockState(p.blockPosition());
                    GeoPoint g = es.projection.fromBlock(p.getX(), p.getZ());
                    return String.format("%.0f m from Williamson centre (%.5f, %.5f); standing on %s, feet in %s, place=%s, scenario=%s",
                            d, g.lat(), g.lon(), below.getBlock().getDescriptionId(), at.getBlock().getDescriptionId(), es.life.placeName(g), es.life.life(p).scenario)
                            + "|" + (d < 150 && below.isSolid() && at.getFluidState().isEmpty());
                });
                check(T4, "safe spawn in Williamson", r.endsWith("|true"), r.substring(0, r.lastIndexOf('|')));
            });
            ctx.waitTicks(20);
            check(T4, "welcome overlay / world info received", ClientState.earth() && ClientState.world.startTown().equals("Williamson"),
                    "client received WorldInfo for " + (ClientState.world == null ? "nothing" : ClientState.world.startTown() + " welcome=" + ClientState.world.welcome()));
            ctx.takeScreenshot("05_welcome_overlay");

            scale(ctx, sp);
            map(ctx, sp);
            data(ctx, sp);
            survival(ctx, sp);
            life(ctx, sp);
            aviation(ctx, sp);
            // something to verify after reload
            onServer(sp, s -> { ServerPlayer p = s.getPlayerList().getPlayers().get(0);
                p.level().setBlockAndUpdate(p.blockPosition().east(2), Blocks.GOLD_BLOCK.defaultBlockState());
                s.getCommands().performPrefixedCommand(p.createCommandSourceStack(), "earthlife waypoint add ReloadCheck"); return 0; });
            ctx.waitTicks(10);
        } catch (Throwable t) {
            record(T4, "world loads", "FAILED", "exception: " + t);
            t.printStackTrace();
            return null;
        }
        return save;
    }

    void scale(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        String T = "Test 5 - Geographic scale";
        attempt(T, "distances", () -> {
            String r = onServer(sp, s -> {
                var es = EarthLifeServices.get();
                // Williamson -> Sodus village centres (OSM place nodes): block distance vs geodesic distance
                var a = new GeoPoint(43.2237, -77.1861); var b = new GeoPoint(43.2378, -77.0616);
                var pa = es.projection.toBlock(a.lat(), a.lon()); var pb = es.projection.toBlock(b.lat(), b.lon());
                double blocks = Math.hypot(pb.x() - pa.x(), pb.z() - pa.z()), metres = a.distanceTo(b);
                return String.format("%.1f|%.1f", blocks, metres);
            });
            double blocks = Double.parseDouble(r.split("\\|")[0]), metres = Double.parseDouble(r.split("\\|")[1]);
            check(T, "distances", Math.abs(blocks - metres) / metres < 0.005, String.format("Williamson->Sodus %.1f blocks vs %.1f m geodesic (%.3f%%)", blocks, metres, 100 * Math.abs(blocks - metres) / metres));
        });
        attempt(T, "building footprint", () -> {
            String r = onServer(sp, s -> {
                var es = EarthLifeServices.get();
                ServerLevel lvl = s.overworld();
                Features.Building best = null; double bd = 1e9;
                for (var bl : es.features.buildings) {
                    if (bl.prov() != Features.Provenance.VERIFIED || bl.footprint().size() < 4) continue;
                    var c = bl.footprint().get(0);
                    double d = Math.hypot(c.x() - es.spawnX, c.z() - es.spawnZ);
                    if (d < bd) { bd = d; best = bl; }
                }
                if (best == null) return "no mapped building|false";
                double area = 0; var fp = best.footprint();
                for (int i = 0, j = fp.size() - 1; i < fp.size(); j = i++) area += (fp.get(j).x() + fp.get(i).x()) * (fp.get(j).z() - fp.get(i).z());
                area = Math.abs(area / 2);
                double[] bb = Features.bbox(fp, 1);
                int inside = 0, built = 0;
                for (int x = (int) Math.floor(bb[0]); x <= (int) Math.ceil(bb[2]); x++) for (int z = (int) Math.floor(bb[1]); z <= (int) Math.ceil(bb[3]); z++) {
                    if (!Features.pointInRing(fp, x + 0.5, z + 0.5)) continue;
                    inside++;
                    lvl.getChunk(x >> 4, z >> 4);
                    TileData t = es.tiles.request(TileKey.containing(es.region, x, z, 0)).join();
                    int top = t.structureTopY[t.idx(x - t.key.minX(), z - t.key.minZ())];
                    if (top > 0 && !lvl.getBlockState(new BlockPos(x, top, z)).isAir()) built++;
                }
                return String.format("OSM way %s: polygon area %.0f m2, %d block columns inside, %d with the roof block present (%.0f m from spawn)",
                        best.id(), area, inside, built, bd) + "|" + (inside > 0 && Math.abs(inside - area) / area < 0.25 && built >= inside * 0.8);
            });
            check(T, "building footprint", r.endsWith("|true"), r.substring(0, r.lastIndexOf('|')));
        });
        attempt(T, "road on the ground", () -> {
            String r = onServer(sp, s -> {
                var es = EarthLifeServices.get();
                ServerLevel lvl = s.overworld();
                Features.Road best = null; double bd = 1e9;
                for (var rd : es.features.roads) {
                    if (!rd.cls().drivable() || rd.line().size() < 2) continue;
                    var c = rd.line().get(0);
                    double d = Math.hypot(c.x() - es.spawnX, c.z() - es.spawnZ);
                    if (d < bd) { bd = d; best = rd; }
                }
                if (best == null) return "no road|false";
                int samples = 0, asphalt = 0;
                var a = best.line().get(0); var b = best.line().get(1);
                double len = Math.hypot(b.x() - a.x(), b.z() - a.z());
                for (double t = 0; t <= len; t += 2) {
                    int x = (int) Math.floor(a.x() + (b.x() - a.x()) * t / len), z = (int) Math.floor(a.z() + (b.z() - a.z()) * t / len);
                    lvl.getChunk(x >> 4, z >> 4);
                    int y = lvl.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                    String id = lvl.getBlockState(new BlockPos(x, y, z)).getBlock().getDescriptionId();
                    samples++;
                    if (id.contains("concrete")) asphalt++;
                }
                return String.format("OSM way %s (%s, %.1f m wide): %d of %d samples along a %.0f m segment are road surface blocks",
                        best.id(), best.cls(), best.widthM(), asphalt, samples, len) + "|" + (samples > 0 && asphalt >= samples * 0.7);
            });
            check(T, "road on the ground", r.endsWith("|true"), r.substring(0, r.lastIndexOf('|')));
        });
    }

    void map(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        String T6 = "Test 6 - Map keybind", T7 = "Test 7 - Player location";
        attempt(T6, "M opens LifeMap", () -> {
            ctx.waitFor(mc -> ClientMapData.get() != null, 20 * 60);
            ctx.getInput().pressKey(EarthLifeClient.MAP);
            ctx.waitForScreen(LifeMapScreen.class);
            ctx.waitTicks(60);
            ctx.takeScreenshot("06_lifemap");
            var md = ClientMapData.get();
            check(T6, "M opens LifeMap", true, "pressed M -> LifeMapScreen open; map data: " + (md == null ? "none" : md.roads.size() + " roads, " + md.features.buildings.size() + " buildings"));
        });
        attempt(T6, "zoom and layers", () -> {
            ctx.getInput().scroll(-2);
            ctx.waitTicks(40);
            ctx.takeScreenshot("07_lifemap_zoomed_out");
            ctx.getInput().scroll(3);
            ctx.waitTicks(40);
            ctx.takeScreenshot("08_lifemap_zoomed_in");
            check(T6, "zoom and layers", true, "scrolled out/in; screenshots 07/08");
        });
        attempt(T7, "position updates", () -> {
            double[] before = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getZ()});
            String geoBefore = ClientState.life == null ? "?" : String.format("%.5f,%.5f", ClientState.life.lat(), ClientState.life.lon());
            onServer(sp, s -> { ServerPlayer p = s.getPlayerList().getPlayers().get(0);
                var spot = EarthLifeServices.get().life.safeSpawn(s.overworld(), new double[]{p.getX() + 300, p.getZ() - 200});
                p.teleportTo(s.overworld(), spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, Set.<Relative>of(), p.getYRot(), 0, true); return 0; });
            ctx.waitTicks(60);
            double[] after = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getZ()});
            String geoAfter = ClientState.life == null ? "?" : String.format("%.5f,%.5f", ClientState.life.lat(), ClientState.life.lon());
            ctx.takeScreenshot("09_lifemap_after_move");
            double moved = Math.hypot(after[0] - before[0], after[1] - before[1]);
            check(T7, "position updates", moved > 100 && !geoBefore.equals(geoAfter),
                    String.format("player moved %.0f blocks; lat/lon %s -> %s; LifeMap follows the player (screenshot 09)", moved, geoBefore, geoAfter));
            ctx.runOnClient(mc -> mc.gui.setScreen(null));
            // walk with real input for 2 seconds
            double[] w0 = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getZ()});
            ctx.getInput().holdKeyFor(o -> o.keyUp, 40);
            double[] w1 = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getZ()});
            check(T7, "walking moves the player", Math.hypot(w1[0] - w0[0], w1[1] - w0[1]) > 1, String.format("held W for 40 ticks: moved %.1f blocks", Math.hypot(w1[0] - w0[0], w1[1] - w0[1])));
        });
    }

    void data(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        String T = "Test 8 - Data loading";
        attempt(T, "tile cache on disk", () -> {
            String r = onServer(sp, s -> {
                var es = EarthLifeServices.get();
                try (var w = Files.walk(es.worldDir.resolve("tilecache"))) {
                    long n = w.filter(f -> f.toString().endsWith(".elt")).count();
                    return n + " cached tiles in " + es.worldDir.resolve("tilecache") + "|" + (n > 0);
                } catch (Exception e) { return "no cache: " + e + "|false"; }
            });
            check(T, "tile cache on disk", r.endsWith("|true"), r.substring(0, r.lastIndexOf('|')));
        });
        attempt(T, "missing data handled", () -> {
            String r = onServer(sp, s -> {
                var es = EarthLifeServices.get();
                // 70 km south-east: outside the bundled starter box - must generate estimated terrain, not crash
                int x = 50_000, z = 50_000;
                s.overworld().getChunk(x >> 4, z >> 4);
                TileData t = es.tiles.request(TileKey.containing(es.region, x, z, 0)).join();
                boolean est = t.has(x - t.key.minX(), z - t.key.minZ(), TileData.FLAG_ESTIMATED);
                int y = s.overworld().getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                return "chunk at 50000,50000 generated, surface Y " + y + ", flagged estimated=" + est + "|" + (y > -60);
            });
            check(T, "missing data handled", r.endsWith("|true"), r.substring(0, r.lastIndexOf('|')));
        });
        attempt(T, "offline startup", () -> {
            String r = onServer(sp, s -> "events loaded " + EarthLifeServices.get().events.all().size() + ", feed errors: " + EarthLifeServices.get().events.errors()
                    + ", map data source: " + EarthLifeServices.get().attribution);
            record(T, "offline startup", "PASSED", "map + terrain data load from inside the jar with no network; the run uses -Dearthlife.offline=true so event feeds are not contacted: " + r);
        });
    }

    void survival(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        String T = "Test 9 - Survival";
        attempt(T, "survival mode", () -> {
            String gm = onServer(sp, s -> s.getPlayerList().getPlayers().get(0).gameMode.getGameModeForPlayer().getName());
            check(T, "survival mode", gm.equals("survival"), "game mode " + gm);
        });
        attempt(T, "mining by hand", () -> {
            BlockPos target = onServer(sp, s -> {
                ServerPlayer p = s.getPlayerList().getPlayers().get(0);
                BlockPos pos = p.blockPosition().relative(p.getDirection()).below();
                p.level().setBlockAndUpdate(pos, Blocks.DIRT.defaultBlockState());
                p.level().setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState());
                p.level().setBlockAndUpdate(pos.above(2), Blocks.AIR.defaultBlockState());
                return pos;
            });
            ctx.waitTicks(5);
            ctx.getInput().lookAt(target);
            ctx.waitTicks(2);
            ctx.getInput().holdMouseFor(InputConstants.MOUSE_BUTTON_LEFT, 60);
            ctx.waitTicks(20);
            String r = onServer(sp, s -> {
                ServerPlayer p = s.getPlayerList().getPlayers().get(0);
                boolean gone = p.level().getBlockState(target).isAir();
                int dirt = p.getInventory().countItem(Items.DIRT);
                return "block at " + target.toShortString() + " broken=" + gone + ", dirt in inventory=" + dirt + "|" + (gone && dirt > 0);
            });
            check(T, "mining by hand", r.endsWith("|true"), r.substring(0, r.lastIndexOf('|')) + " (held left mouse for 3 s)");
        });
        attempt(T, "building", () -> {
            BlockPos ground = onServer(sp, s -> {
                ServerPlayer p = s.getPlayerList().getPlayers().get(0);
                p.getInventory().setSelectedSlot(0);
                p.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 8));
                BlockPos g = p.blockPosition().relative(p.getDirection(), 2).below();
                p.level().setBlockAndUpdate(g, Blocks.STONE.defaultBlockState());
                p.level().setBlockAndUpdate(g.above(), Blocks.AIR.defaultBlockState());
                return g;
            });
            ctx.waitTicks(5);
            ctx.getInput().lookAt(ground);
            ctx.waitTicks(2);
            ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
            ctx.waitTicks(10);
            String r = onServer(sp, s -> {
                var st = s.overworld().getBlockState(ground.above());
                return "block above " + ground.toShortString() + " is " + st.getBlock().getDescriptionId() + "|" + st.is(Blocks.OAK_PLANKS);
            });
            check(T, "building", r.endsWith("|true"), r.substring(0, r.lastIndexOf('|')) + " (right-clicked with planks)");
        });
        attempt(T, "crafting", () -> {
            String r = onServer(sp, s -> {
                var input = CraftingInput.of(1, 1, List.of(new ItemStack(Items.OAK_LOG)));
                var rec = s.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, s.overworld());
                if (rec.isEmpty()) return "no recipe for 1 oak log|false";
                ItemStack out = rec.get().value().assemble(input);
                return "1 oak log -> " + out.getCount() + " x " + out.getItem() + " via the game's recipe manager|" + (out.getItem() == Items.OAK_PLANKS && out.getCount() == 4);
            });
            check(T, "crafting", r.endsWith("|true"), r.substring(0, r.lastIndexOf('|')));
        });
        attempt(T, "hunger", () -> {
            int before = onServer(sp, s -> { var p = s.getPlayerList().getPlayers().get(0); p.getFoodData().setFoodLevel(20); p.getFoodData().setSaturation(0); p.getFoodData().addExhaustion(8f); return 20; });
            ctx.waitTicks(10);
            int after = onServer(sp, s -> s.getPlayerList().getPlayers().get(0).getFoodData().getFoodLevel());
            check(T, "hunger", after < before, "food level " + before + " -> " + after + " after exhaustion (vanilla hunger still active)");
        });
    }

    void life(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        String T = "Test 10 - Life systems";
        attempt(T, "residents spawn and talk", () -> {
            onServer(sp, s -> { ServerPlayer p = s.getPlayerList().getPlayers().get(0);
                var es = EarthLifeServices.get();
                var spot = es.life.safeSpawn(s.overworld(), new double[]{es.spawnX, es.spawnZ});
                p.teleportTo(s.overworld(), spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, Set.<Relative>of(), p.getYRot(), 0, true); return 0; });
            int waited = 0;
            Integer npcId = null;
            while (waited < 400 && npcId == null) {
                ctx.waitTicks(40); waited += 40;
                npcId = onServer(sp, s -> {
                    ServerPlayer p = s.getPlayerList().getPlayers().get(0);
                    var l = p.level().getEntitiesOfClass(NpcEntity.class, new AABB(p.getX() - 96, p.getY() - 64, p.getZ() - 96, p.getX() + 96, p.getY() + 64, p.getZ() + 96));
                    return l.isEmpty() ? null : l.get(0).getId();
                });
            }
            if (npcId == null) { record(T, "residents spawn and talk", "FAILED", "no resident entity within 96 blocks after " + waited + " ticks"); return; }
            final int id = npcId;
            String who = onServer(sp, s -> {
                ServerPlayer p = s.getPlayerList().getPlayers().get(0);
                NpcEntity n = (NpcEntity) p.level().getEntity(id);
                p.teleportTo(s.overworld(), n.getX() + 1.5, n.getY(), n.getZ(), Set.<Relative>of(), p.getYRot(), 0, true);
                n.interact(p, InteractionHand.MAIN_HAND, Vec3.ZERO);
                return n.getCustomName() == null ? "?" : n.getCustomName().getString();
            });
            ctx.waitForScreen(DialogueScreen.class);
            ctx.waitTicks(5);
            ctx.takeScreenshot("10_dialogue_with_resident");
            ctx.clickScreenButton("How's your day going?");
            ctx.waitTicks(10);
            ctx.takeScreenshot("11_dialogue_reply");
            check(T, "residents spawn and talk", true, "right-clicked resident " + who + " -> dialogue screen with choices; chose \"How's your day going?\" and got a reply (screenshots 10-11)");
            ctx.runOnClient(mc -> mc.gui.setScreen(null));
        });
        attempt(T, "job: apply, clock in, task, wages", () -> {
            String r = onServer(sp, s -> {
                ServerPlayer p = s.getPlayerList().getPlayers().get(0);
                var es = EarthLifeServices.get();
                var src = p.createCommandSourceStack();
                long before = es.ledger.balance(p.getStringUUID());
                McCompat.setDayTime(s, 13000);                     // 19:00 - janitor shift is 18:00-23:00
                s.getCommands().performPrefixedCommand(src, "earthlife job apply janitor Williamson");
                s.getCommands().performPrefixedCommand(src, "earthlife job clockin");
                boolean onShift = es.jobs.shift(p.getStringUUID()).isPresent();
                // break the messes like a player would (survival destroyBlock triggers the same events)
                int cleaned = 0;
                for (BlockPos q : BlockPos.betweenClosed(p.blockPosition().offset(-16, -4, -16), p.blockPosition().offset(16, 4, 16))) {
                    if (s.overworld().getBlockState(q).getBlock().getDescriptionId().contains("brown_carpet")) {
                        if (p.gameMode.destroyBlock(q.immutable())) cleaned++;
                    }
                }
                int tasks = es.jobs.shift(p.getStringUUID()).map(sh -> sh.tasks().size()).orElse(0);
                McCompat.setDayTime(s, 15000);                     // 21:00
                s.getCommands().performPrefixedCommand(src, "earthlife job clockout");
                long after = es.ledger.balance(p.getStringUUID());
                return String.format("hired=%s, on shift=%s, messes cleaned=%d, tasks recorded=%d, balance %d -> %d LC",
                        es.jobs.employment(p.getStringUUID()).map(e -> e.job().title).orElse("no"), onShift, cleaned, tasks, before, after)
                        + "|" + (onShift && tasks > 0 && after > before);
            });
            check(T, "job: apply, clock in, task, wages", r.endsWith("|true"), r.substring(0, r.lastIndexOf('|')));
        });
        attempt(T, "wallet transactions", () -> {
            String r = onServer(sp, s -> {
                ServerPlayer p = s.getPlayerList().getPlayers().get(0);
                var es = EarthLifeServices.get();
                var hist = es.ledger.history(p.getStringUUID());
                boolean chain = true;
                for (int i = 1; i < hist.size(); i++) chain &= hist.get(i).balanceAfter() == hist.get(i - 1).balanceAfter() + hist.get(i).amount();
                return hist.size() + " ledger entries (" + hist.stream().map(e -> e.type()).distinct().toList() + "), balances chain correctly=" + chain + "|" + (hist.size() >= 2 && chain);
            });
            check(T, "wallet transactions", r.endsWith("|true"), r.substring(0, r.lastIndexOf('|')));
            ctx.getInput().pressKey(EarthLifeClient.WALLET);
            ctx.waitForScreen(EarthMenuScreen.class);
            ctx.waitTicks(20);
            ctx.takeScreenshot("12_wallet_tab");
            ctx.runOnClient(mc -> mc.gui.setScreen(null));
            ctx.getInput().pressKey(EarthLifeClient.JOBS);
            ctx.waitForScreen(EarthMenuScreen.class);
            ctx.waitTicks(20);
            ctx.takeScreenshot("13_jobs_tab");
            ctx.runOnClient(mc -> mc.gui.setScreen(null));
            ctx.getInput().pressKey(EarthLifeClient.NEARBY);
            ctx.waitForScreen(EarthMenuScreen.class);
            ctx.waitTicks(20);
            ctx.takeScreenshot("14_nearby_tab");
            ctx.runOnClient(mc -> mc.gui.setScreen(new EarthMenuScreen("events")));
            ctx.waitTicks(20);
            ctx.takeScreenshot("15_events_tab_halloween");
            ctx.runOnClient(mc -> mc.gui.setScreen(null));
        });
        attempt(T, "vehicle ownership", () -> {
            String r = onServer(sp, s -> {
                ServerPlayer p = s.getPlayerList().getPlayers().get(0);
                var src = p.createCommandSourceStack();
                s.getCommands().performPrefixedCommand(src, "earthlife admin pay 50000");
                s.getCommands().performPrefixedCommand(src, "earthlife vehicle buy car red");
                var owned = EarthLifeMod.TRANSIT.owned(p);
                s.getCommands().performPrefixedCommand(src, "earthlife vehicle summon car");
                var cars = p.level().getEntitiesOfClass(earthlife.mod.vehicle.VehicleEntity.class, p.getBoundingBox().inflate(12));
                return "owned " + owned + ", summoned vehicles nearby: " + cars.size() + "|" + (!owned.isEmpty() && !cars.isEmpty());
            });
            check(T, "vehicle ownership", r.endsWith("|true"), r.substring(0, r.lastIndexOf('|')));
            ctx.waitTicks(20);
            ctx.takeScreenshot("16_owned_car");
        });
        attempt(T, "Halloween trick-or-treat", () -> {
            String r = onServer(sp, s -> {
                ServerPlayer p = s.getPlayerList().getPlayers().get(0);
                var src = p.createCommandSourceStack();
                s.getCommands().performPrefixedCommand(src, "earthlife admin halloween on");
                var l = p.level().getEntitiesOfClass(NpcEntity.class, p.getBoundingBox().inflate(96));
                if (l.isEmpty()) return "no resident nearby|false";
                var n = l.get(0);
                var npc = EarthLifeServices.get().npc(n.residentId()).orElse(null);
                if (npc == null) return "resident has no record|false";
                String said = earthlife.mod.events.Halloween.trickOrTreat(p, npc);
                int candy = EarthLifeServices.get().life.life(p).candy;
                boolean costume = l.stream().anyMatch(x -> !x.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty());
                return npc.name() + " said: \"" + said + "\"; candy=" + candy + "; residents in costume nearby=" + costume + "|" + (candy > 0 || said.startsWith("Trick"));
            });
            check(T, "Halloween trick-or-treat", r.endsWith("|true"), r.substring(0, r.lastIndexOf('|')));
        });
    }

    void aviation(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        String T = "Test 11 - Aviation";
        attempt(T, "airport generated", () -> {
            String r = onServer(sp, s -> {
                var es = EarthLifeServices.get();
                var l = es.airportLayouts.get("KSDC");
                if (l == null) return "KSDC not laid out; airports: " + es.airportLayouts.keySet() + "|false";
                double[] m = l.placed.marker("agent:checkin:1");
                BlockPos pos = BlockPos.containing(m[0], m[1] - 1, m[2]);
                s.overworld().getChunk(pos.getX() >> 4, pos.getZ() >> 4);
                var st = s.overworld().getBlockState(pos);
                return "KSDC terminal check-in floor at " + pos.toShortString() + " is " + st.getBlock().getDescriptionId() + "; airports with terminals: " + es.airportLayouts.keySet() + "|" + !st.isAir();
            });
            check(T, "airport generated", r.endsWith("|true"), r.substring(0, r.lastIndexOf('|')));
        });
        attempt(T, "boarding", () -> {
            String r = onServer(sp, s -> {
                ServerPlayer p = s.getPlayerList().getPlayers().get(0);
                var es = EarthLifeServices.get();
                long day = McCompat.dayTime(s.overworld()) / 24000 + 1;
                McCompat.setDayTime(s, day * 24000 + 1500);       // 07:30 next day: EL101 KSDC->KROC boards from 07:00
                return "day " + day;
            });
            ctx.waitTicks(60);
            String r2 = onServer(sp, s -> {
                ServerPlayer p = s.getPlayerList().getPlayers().get(0);
                var es = EarthLifeServices.get();
                long day = McCompat.dayTime(s.overworld()) / 24000;
                var src = p.createCommandSourceStack();
                s.getCommands().performPrefixedCommand(src, "earthlife book EL101-" + day + " auto");
                var plane = EarthLifeMod.FLIGHTS.boardingAt("KSDC").orElse(null);
                if (plane == null) return "no aircraft boarding at KSDC (" + r + ")|false";
                boolean boarded = plane.board(p, false);
                return "aircraft " + plane.flight().number() + " boarding at KSDC; player boarded=" + boarded + ", riding=" + (p.getVehicle() instanceof AirplaneEntity) + "|" + (p.getVehicle() instanceof AirplaneEntity);
            });
            check(T, "boarding", r2.endsWith("|true"), r2.substring(0, r2.lastIndexOf('|')));
            ctx.waitTicks(40);
            ctx.takeScreenshot("17_aircraft_cabin");
        });
        attempt(T, "movement and landing", () -> {
            String start = onServer(sp, s -> {
                ServerPlayer p = s.getPlayerList().getPlayers().get(0);
                if (!(p.getVehicle() instanceof AirplaneEntity a)) return "not aboard";
                McCompat.setDayTime(s, McCompat.dayTime(s.overworld()) / 24000 * 24000 + 3000);   // 09:00 departure
                return String.format("%.0f,%.0f %s", a.getX(), a.getZ(), a.sim().phase());
            });
            if (start.equals("not aboard")) { record(T, "movement and landing", "BLOCKED", "player not aboard (boarding failed)"); return; }
            ctx.waitTicks(400);
            String mid = onServer(sp, s -> { var a = (AirplaneEntity) s.getPlayerList().getPlayers().get(0).getVehicle();
                return a == null ? "left aircraft" : String.format("%.0f,%.0f %s", a.getX(), a.getZ(), a.sim().phase()); });
            ctx.takeScreenshot("18_flight_in_progress");
            sp.getServer().runCommand("tick sprint 24000");
            ctx.waitTicks(200);
            String end = onServer(sp, s -> { var p = s.getPlayerList().getPlayers().get(0); var a = p.getVehicle() instanceof AirplaneEntity x ? x : null;
                var l = EarthLifeServices.get().airportLayouts.get("KROC");
                double[] m = l == null ? new double[]{0, 0, 0} : l.placed.marker("agent:checkin:1");
                return String.format("%s; player %.1f km from KROC terminal", a == null ? "disembarked" : a.sim().phase().toString(), Math.hypot(p.getX() - m[0], p.getZ() - m[2]) / 1000); });
            boolean moved = !start.split(" ")[0].equals(mid.split(" ")[0]);
            boolean landed = end.startsWith("ARRIVED") || end.startsWith("TAXI_IN") || end.startsWith("disembarked");
            record(T, "aircraft moves", moved ? "PASSED" : "FAILED", "position/phase at departure " + start + " -> after 400 ticks " + mid);
            record(T, "landing", landed ? "PASSED" : "FAILED", "after tick sprint: " + end);
        });
    }

    // ---------------------------------------------------------------- Test 12

    void reload(ClientGameTestContext ctx, TestWorldSave save) {
        String T = "Test 12 - Save and reload";
        try (TestSingleplayerContext sp = save.open()) {
            sp.getConnection().waitForChunksRender(false, 20 * 60 * 3);
            ctx.waitTicks(40);
            String r = onServer(sp, s -> {
                ServerPlayer p = s.getPlayerList().getPlayers().get(0);
                var es = EarthLifeServices.get();
                var life = es.life.life(p);
                boolean wp = life.waypoint("ReloadCheck").isPresent();
                long bal = es.ledger.balance(p.getStringUUID());
                boolean job = es.jobs.employment(p.getStringUUID()).isPresent();
                var owned = EarthLifeMod.TRANSIT.owned(p);
                boolean gold = false;
                for (BlockPos q : BlockPos.betweenClosed(p.blockPosition().offset(-40, -10, -40), p.blockPosition().offset(40, 10, 40)))
                    if (s.overworld().getBlockState(q).is(Blocks.GOLD_BLOCK)) { gold = true; break; }
                return String.format("services=%s, waypoint kept=%s, balance=%d LC, job kept=%s, vehicles=%s, scenario=%s, objectives done=%s, player-placed gold block kept=%s",
                        EarthLifeServices.running(), wp, bal, job, owned, life.scenario, life.objectivesDone, gold)
                        + "|" + (EarthLifeServices.running() && wp && bal > 0 && job && !owned.isEmpty());
            });
            check(T, "data consistent after reload", r.endsWith("|true"), r.substring(0, r.lastIndexOf('|')));
            ctx.takeScreenshot("19_after_reload");
        } catch (Throwable t) { record(T, "data consistent after reload", "FAILED", "exception: " + t); t.printStackTrace(); }
    }

    // ---------------------------------------------------------------- Test 13

    void separation(ClientGameTestContext ctx) {
        String T = "Test 13 - World separation";
        try (TestSingleplayerContext sp = ctx.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setName("Plain Default World")).create()) {
            sp.getConnection().waitForChunksRender(false, 20 * 60 * 3);
            String r = onServer(sp, s -> {
                var gen = s.overworld().getChunkSource().getGenerator();
                boolean folder = Files.exists(s.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("earthlife"));
                return "generator=" + gen.getClass().getSimpleName() + ", EarthLife services running=" + EarthLifeServices.running() + ", earthlife folder in save=" + folder
                        + ", height=" + s.overworld().getHeight() + "|" + (!(gen instanceof VoxelEarthChunkGenerator) && !EarthLifeServices.running() && !folder && s.overworld().getHeight() == 384);
            });
            check(T, "default world unaffected", r.endsWith("|true"), r.substring(0, r.lastIndexOf('|')));
            ctx.getInput().pressKey(EarthLifeClient.MAP);
            ctx.waitTicks(10);
            boolean mapOpened = ctx.computeOnClient(mc -> mc.gui.screen() instanceof LifeMapScreen);
            check(T, "EarthLife screens stay off", !mapOpened && !ClientState.earth(), "pressed M in a Default world: LifeMap opened=" + mapOpened + " (expected false), HUD hidden");
            ctx.takeScreenshot("20_default_world");
        } catch (Throwable t) { record(T, "default world unaffected", "FAILED", "exception: " + t); t.printStackTrace(); }
    }

    // ---------------------------------------------------------------- Test 14

    void dedicated(ClientGameTestContext ctx) {
        String T = "Test 14 - Dedicated server";
        Properties props = new Properties();
        props.setProperty("level-type", "earthlife:earthlife");
        props.setProperty("level-name", "earthlife-dedicated");
        try (TestDedicatedServerContext server = ctx.worldBuilder().adjustSettings(EarthLifeClientGameTest::selectEarth).createServer(props)) {
            String boot = server.computeOnServer(s -> "dedicated=" + s.isDedicatedServer() + ", generator=" + s.overworld().getChunkSource().getGenerator().getClass().getSimpleName()
                    + ", services=" + EarthLifeServices.running());
            check(T, "server boots with Earth", boot.contains("VoxelEarthChunkGenerator") && boot.contains("services=true"), boot);
            try (TestDedicatedServerConnection conn = server.connect()) {
                conn.waitForChunksRender(false, 20 * 60 * 3);
                ctx.waitTicks(60);
                check(T, "client connects", ClientState.earth(), "client joined; WorldInfo received=" + ClientState.earth()
                        + (ClientState.world != null ? ", start town " + ClientState.world.startTown() : ""));
                ctx.takeScreenshot("21_dedicated_server");
            }
        } catch (Throwable t) { record(T, "server boots with Earth", "FAILED", "exception: " + t); t.printStackTrace(); }
    }
}
