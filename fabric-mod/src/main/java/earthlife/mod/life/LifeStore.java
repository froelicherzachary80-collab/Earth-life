package earthlife.mod.life;

import earthlife.core.util.Json;
import earthlife.core.world.AtomicFiles;
import earthlife.mod.EarthLifeMod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Per-player life records for the current world. Files are written atomically (temp file + move), so a crash
 * mid-save can't leave a half-written record; a corrupt file is kept aside as *.corrupt and a fresh record starts.
 */
public final class LifeStore {
    private final Path dir;
    private final Map<String, PlayerLife> cache = new HashMap<>();

    public LifeStore(Path worldDir) throws IOException {
        dir = worldDir.resolve("players");
        Files.createDirectories(dir);
    }

    public synchronized PlayerLife get(String uuid) {
        return cache.computeIfAbsent(uuid, u -> {
            Path f = dir.resolve(u + ".json");
            if (Files.exists(f)) {
                try { return PlayerLife.fromJson(u, Json.obj(Json.parse(Files.readString(f)))); }
                catch (Exception e) {
                    EarthLifeMod.LOG.error("EarthLife: player record {} unreadable, starting fresh (kept as .corrupt)", f, e);
                    try { Files.move(f, f.resolveSibling(u + ".json.corrupt"), StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) {}
                }
            }
            PlayerLife p = new PlayerLife(u);
            p.dirty = true;
            return p;
        });
    }

    public synchronized Collection<PlayerLife> loaded() { return List.copyOf(cache.values()); }

    public synchronized void saveDirty() {
        for (PlayerLife p : cache.values()) if (p.dirty) {
            try { AtomicFiles.write(dir.resolve(p.uuid + ".json"), Json.write(p.toJson()).getBytes(StandardCharsets.UTF_8)); p.dirty = false; }
            catch (IOException e) { EarthLifeMod.LOG.error("EarthLife: could not save player {}", p.uuid, e); }
        }
    }
}
