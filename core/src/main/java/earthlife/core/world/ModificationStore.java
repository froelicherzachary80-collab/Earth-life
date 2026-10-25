package earthlife.core.world;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

/**
 * Persists player block edits per tile as sparse deltas (x,y,z -> block id string), so generated tiles
 * can be discarded on unload and regenerated later without losing changes. Each tile's delta file
 * is checksummed and written atomically; a corrupt file is quarantined (renamed *.corrupt), never silently loaded.
 */
public final class ModificationStore {
    public record BlockEdit(int x, int y, int z, String blockId) {}

    private final Path dir;
    private final Map<String, Map<Long, BlockEdit>> cache = new ConcurrentHashMap<>();
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();

    public ModificationStore(Path dir) { this.dir = dir; }

    private static String tileId(String region, int x, int z) {
        return region + "/m_" + Math.floorDiv(x, TileKey.SIZE) + "_" + Math.floorDiv(z, TileKey.SIZE);
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public void record(String region, int x, int y, int z, String blockId) {
        String id = tileId(region, x, z);
        tile(id).put(pack(x, y, z), new BlockEdit(x, y, z, blockId));
        dirty.add(id);
    }

    public Collection<BlockEdit> editsForTile(String region, int tx, int tz) {
        return Collections.unmodifiableCollection(tile(region + "/m_" + tx + "_" + tz).values());
    }

    private Map<Long, BlockEdit> tile(String id) {
        return cache.computeIfAbsent(id, this::load);
    }

    private Map<Long, BlockEdit> load(String id) {
        Map<Long, BlockEdit> m = new ConcurrentHashMap<>();
        Path p = dir.resolve(id + ".eld");
        if (!Files.exists(p)) return m;
        try {
            byte[] b = Files.readAllBytes(p);
            CRC32 crc = new CRC32();
            crc.update(b, 0, b.length - 8);
            long stored = new DataInputStream(new ByteArrayInputStream(b, b.length - 8, 8)).readLong();
            if (stored != crc.getValue()) throw new IOException("checksum mismatch");
            var in = new DataInputStream(new ByteArrayInputStream(b, 0, b.length - 8));
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int x = in.readInt(), y = in.readInt(), z = in.readInt();
                String block = in.readUTF();
                m.put(pack(x, y, z), new BlockEdit(x, y, z, block));
            }
        } catch (IOException | RuntimeException e) {
            try { Files.move(p, p.resolveSibling(p.getFileName() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) {}
            System.err.println("[EarthLife] quarantined corrupt edit file " + p + ": " + e.getMessage());
        }
        return m;
    }

    /** Writes all dirty tiles. Called on autosave, tile unload and world close. */
    public synchronized void flush() throws IOException {
        for (String id : new ArrayList<>(dirty)) {
            Map<Long, BlockEdit> m = cache.get(id);
            var bo = new ByteArrayOutputStream();
            var out = new DataOutputStream(bo);
            out.writeInt(m.size());
            for (BlockEdit e : m.values()) { out.writeInt(e.x()); out.writeInt(e.y()); out.writeInt(e.z()); out.writeUTF(e.blockId()); }
            out.flush();
            byte[] body = bo.toByteArray();
            CRC32 crc = new CRC32();
            crc.update(body);
            var full = new ByteArrayOutputStream();
            full.write(body);
            new DataOutputStream(full).writeLong(crc.getValue());
            AtomicFiles.write(dir.resolve(id + ".eld"), full.toByteArray());
            dirty.remove(id);
        }
    }

    /** Drop in-memory deltas for a tile once flushed (they reload from disk on demand). */
    public void evict(String region, int tx, int tz) {
        String id = region + "/m_" + tx + "_" + tz;
        if (!dirty.contains(id)) cache.remove(id);
    }
}
