package earthlife.core.world;

import java.io.*;
import java.util.zip.CRC32;

/**
 * Column data for one tile. Engine-agnostic: the Fabric layer maps {@link Surface} to vanilla blocks.
 * Per column: terrain Y, surface type, structure (building) top Y (0 = none), structure kind and flags.
 */
public final class TileData {
    public enum Surface { GRASS, FARMLAND, ORCHARD, FOREST, SAND, WATER, ASPHALT, ROAD_MARKING, GRAVEL_PATH, RUNWAY, TAXIWAY, CONCRETE, DIRT, WETLAND, RAIL_BED }

    public static final byte FLAG_ESTIMATED = 1;   // derived from procedural estimate, not verified data
    public static final byte FLAG_BUILDING = 2;
    public static final byte FLAG_BUILDING_WALL = 4;
    public static final byte FLAG_ROAD = 8;
    public static final byte FLAG_WATER = 16;
    public static final byte FLAG_TREE = 32;
    public static final byte FLAG_RAIL = 64;
    /** Column belongs to a placed structure site (airport, station): terrain flattened, blueprint blocks go here. */
    public static final byte FLAG_SITE = (byte) 0x80;

    public final TileKey key;
    public final int n;
    public final short[] terrainY;
    public final byte[] surface;
    public final short[] structureTopY;
    public final byte[] structureKind;   // index into BuildingKind palette (0 = house, 1 = commercial, 2 = terminal, 3 = tower, 4 = barn, 5 = other)
    public final byte[] flags;
    // V0.3 realistic buildings: wall top (eave), wall/roof material (BuildingStyle ordinals) and extra flags.
    public final short[] eaveY;
    public final byte[] wallMat, roofMat, flags2;
    public static final byte F2_DOOR = 1, F2_CHIMNEY = 2, F2_LANDMARK = 4;

    public TileData(TileKey key) {
        this.key = key;
        this.n = key.samples();
        int c = n * n;
        terrainY = new short[c]; surface = new byte[c]; structureTopY = new short[c]; structureKind = new byte[c]; flags = new byte[c];
        eaveY = new short[c]; wallMat = new byte[c]; roofMat = new byte[c]; flags2 = new byte[c];
    }

    public int idx(int sx, int sz) { return sz * n + sx; }
    public Surface surfaceAt(int sx, int sz) { return Surface.values()[surface[idx(sx, sz)]]; }
    public boolean has(int sx, int sz, byte f) { return (flags[idx(sx, sz)] & f) != 0; }
    public boolean has2(int sx, int sz, byte f) { return (flags2[idx(sx, sz)] & f) != 0; }

    // ---- serialization with checksum (offline cache + save integrity) ----
    private static final int MAGIC = 0x454C5432; // "ELT2" (V0.3 adds eave/material/flags2; ELT1 cache entries are regenerated)

    public byte[] toBytes() {
        try {
            var bo = new ByteArrayOutputStream();
            var out = new DataOutputStream(bo);
            out.writeInt(MAGIC);
            out.writeUTF(key.region()); out.writeInt(key.tx()); out.writeInt(key.tz()); out.writeInt(key.lod());
            for (short s : terrainY) out.writeShort(s);
            out.write(surface);
            for (short s : structureTopY) out.writeShort(s);
            out.write(structureKind);
            out.write(flags);
            for (short x : eaveY) out.writeShort(x);
            out.write(wallMat); out.write(roofMat); out.write(flags2);
            out.flush();
            byte[] body = bo.toByteArray();
            CRC32 crc = new CRC32();
            crc.update(body);
            var full = new ByteArrayOutputStream();
            var fo = new DataOutputStream(full);
            fo.write(body);
            fo.writeLong(crc.getValue());
            return full.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** @throws IOException if the data is truncated or the checksum doesn't match (corrupt cache entry). */
    public static TileData fromBytes(byte[] b) throws IOException {
        if (b.length < 12) throw new IOException("tile too short");
        CRC32 crc = new CRC32();
        crc.update(b, 0, b.length - 8);
        long stored = new DataInputStream(new ByteArrayInputStream(b, b.length - 8, 8)).readLong();
        if (stored != crc.getValue()) throw new IOException("tile checksum mismatch");
        var in = new DataInputStream(new ByteArrayInputStream(b, 0, b.length - 8));
        if (in.readInt() != MAGIC) throw new IOException("bad tile magic");
        TileData t = new TileData(new TileKey(in.readUTF(), in.readInt(), in.readInt(), in.readInt()));
        for (int i = 0; i < t.terrainY.length; i++) t.terrainY[i] = in.readShort();
        in.readFully(t.surface);
        for (int i = 0; i < t.structureTopY.length; i++) t.structureTopY[i] = in.readShort();
        in.readFully(t.structureKind);
        in.readFully(t.flags);
        for (int i = 0; i < t.eaveY.length; i++) t.eaveY[i] = in.readShort();
        in.readFully(t.wallMat); in.readFully(t.roofMat); in.readFully(t.flags2);
        return t;
    }
}
