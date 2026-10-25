package earthlife.test;

import earthlife.core.geo.*;
import earthlife.core.world.*;
import earthlife.core.world.TileData.Surface;

import java.nio.file.*;
import java.util.List;

import static earthlife.test.T.*;

public final class WorldTest {
    static final String FIXTURE = """
        {"attribution":"TEST FIXTURE (synthetic)",
         "roads":[{"id":"r1","class":"PRIMARY","width":8,"line":[[-200,10],[300,10]],"prov":"VERIFIED"},
                  {"id":"rw","class":"RUNWAY","width":30,"line":[[0,600],[1100,600]],"prov":"VERIFIED"}],
         "buildings":[{"id":"b1","kind":"house","levels":2,"footprint":[[20,30],[32,30],[32,40],[20,40]],"prov":"VERIFIED"},
                      {"id":"b2","kind":"barn","levels":1,"footprint":[[100,100],[120,100],[120,115],[100,115]],"prov":"ESTIMATED"}],
         "areas":[{"id":"a1","cover":"ORCHARD","ring":[[-250,-250],[0,-250],[0,0],[-250,0]],"prov":"VERIFIED"}],
         "water":[{"id":"w1","ring":[[400,-300],[500,-300],[500,-200],[400,-200]],"prov":"VERIFIED"}]}
        """;

    static TileGenerator gen() {
        var f = Features.parse(FIXTURE);
        var proj = new LocalProjection(new GeoPoint(43.2237, -77.1861), 63, 1.0);
        return new TileGenerator(new FeatureIndex(f), new ElevationSource.Procedural(100, 0, 1), proj, 42);
    }

    public static void testRoadsBuildingsWaterRasterize() {
        TileData t = gen().generate(new TileKey("r", 0, 0, 0));
        check(t.has(50, 10, TileData.FLAG_ROAD), "road present on centre line");
        check(!t.has(50, 20, TileData.FLAG_ROAD), "no road 10 m away");
        check(t.has(25, 35, TileData.FLAG_BUILDING), "house interior");
        check(t.has(20, 35, TileData.FLAG_BUILDING_WALL), "house wall at footprint edge");
        check(!t.has(26, 35, TileData.FLAG_BUILDING_WALL), "interior is not wall");
        check(t.eaveY[t.idx(25, 35)] == t.terrainY[t.idx(25, 35)] + 6, "2 levels -> walls 6 blocks tall");
        check(t.structureTopY[t.idx(25, 35)] > t.eaveY[t.idx(25, 35)], "pitched house roof rises above the walls");
        check(!t.has(25, 35, TileData.FLAG_ESTIMATED), "verified building not marked estimate");
        check(t.has(110, 107, TileData.FLAG_ESTIMATED), "estimated building keeps estimate flag");
        check(!t.has(200, 200, TileData.FLAG_BUILDING), "empty field");
    }

    public static void testLandcoverAndWaterTilesAcrossBoundaries() {
        var g = gen();
        TileData nw = g.generate(new TileKey("r", -1, -1, 0)); // x,z in [-256,0)
        check(nw.surfaceAt(200, 200) == Surface.ORCHARD, "orchard in negative tile");
        TileData ne = g.generate(new TileKey("r", 1, -2, 0));   // x [256,512), z [-512,-256)
        check(ne.surfaceAt(450 - 256, -280 + 512) == Surface.WATER, "lake");
        check(ne.has(450 - 256, -280 + 512, TileData.FLAG_WATER), "water flag");
    }

    public static void testLodSamplesAreCoarser() {
        var g = gen();
        TileData l0 = g.generate(new TileKey("r", 0, 2, 0)), l2 = g.generate(new TileKey("r", 0, 2, 2));
        check(l0.n == 256 && l2.n == 16, "LOD sizes");
        check(l2.surfaceAt(3, 5) == Surface.RUNWAY || l2.surfaceAt(3, 5) == Surface.ROAD_MARKING, "runway visible from far LOD (z=600)");
    }

    public static void testDeterministic() {
        var a = gen().generate(new TileKey("r", -1, -1, 0)).toBytes();
        var b = gen().generate(new TileKey("r", -1, -1, 0)).toBytes();
        check(java.util.Arrays.equals(a, b), "same input -> identical tile");
    }

    public static void testTileSerializationDetectsCorruption() throws Exception {
        byte[] b = gen().generate(new TileKey("r", 0, 0, 1)).toBytes();
        TileData back = TileData.fromBytes(b);
        check(back.key.equals(new TileKey("r", 0, 0, 1)), "key round trip");
        b[100] ^= 1;
        throwsEx(java.io.IOException.class, () -> TileData.fromBytes(b), "flipped bit must be detected");
    }

    public static void testStreamingLoadsNearUnloadsFarAndUsesDiskCache() throws Exception {
        Path dir = tmpDir();
        var edits = new ModificationStore(dir.resolve("edits"));
        TileStreamer.Rings rings = new TileStreamer.Rings(1, 2, 3);
        try (var s = new TileStreamer(gen(), dir.resolve("cache"), edits, 4, 1000, null)) {
            s.update("r", List.<int[]>of(new int[]{0, 0}), rings);
            s.awaitIdle(20_000);
            check(s.loadedCount() == 49, "7x7 tiles loaded, got " + s.loadedCount());
            check(s.getLoaded(new TileKey("r", 0, 0, 0)) != null, "centre at lod0");
            check(s.getLoaded(new TileKey("r", 3, 0, 2)) != null, "edge at lod2");
            // Move 10 tiles east: old tiles unloaded.
            s.update("r", List.<int[]>of(new int[]{2560, 0}), rings);
            s.awaitIdle(20_000);
            check(s.getLoaded(new TileKey("r", 0, 0, 0)) == null, "far tile unloaded");
            int generatedBefore = s.generatedCount();
            s.update("r", List.<int[]>of(new int[]{0, 0}), rings);
            s.awaitIdle(20_000);
            check(s.generatedCount() == generatedBefore, "returning reuses disk cache, regenerated " + (s.generatedCount() - generatedBefore));
            check(s.diskHits() >= 49, "disk hits " + s.diskHits());
        }
    }

    public static void testPlayerEditsPersistAndCorruptionIsQuarantined() throws Exception {
        Path dir = tmpDir();
        var m = new ModificationStore(dir);
        m.record("r", 5, 70, -3, "minecraft:stone");
        m.record("r", 5, 70, -3, "minecraft:air");      // overwrite same block
        m.record("r", 300, 64, 2, "minecraft:oak_planks");
        m.flush();
        var m2 = new ModificationStore(dir);
        var e = m2.editsForTile("r", 0, -1);
        check(e.size() == 1 && e.iterator().next().blockId().equals("minecraft:air"), "latest edit persisted");
        check(m2.editsForTile("r", 1, 0).size() == 1, "second tile");
        Path f = dir.resolve("r/m_0_-1.eld");
        byte[] b = Files.readAllBytes(f); b[5] ^= 0x40; Files.write(f, b);
        var m3 = new ModificationStore(dir);
        check(m3.editsForTile("r", 0, -1).isEmpty(), "corrupt file not loaded");
        check(Files.exists(dir.resolve("r/m_0_-1.eld.corrupt")), "corrupt file quarantined");
    }
}
