package earthlife.launcher;

import earthlife.core.util.Json;
import earthlife.core.world.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.*;

/** The EarthLife game directory: mods, saves, config. Provides mod manager, save manager and world-creation request. */
public final class Instance {
    public final Path dir;

    public Instance(Path dir) { this.dir = dir; }

    public Path mods() { return dir.resolve("mods"); }
    public Path saves() { return dir.resolve("saves"); }
    public Path backups() { return dir.resolve("backups"); }

    public void ensure() throws IOException {
        Files.createDirectories(mods()); Files.createDirectories(saves()); Files.createDirectories(dir.resolve("config/earthlife"));
    }

    // ---- mod manager ----
    public record Mod(String file, boolean enabled) {}

    public List<Mod> listMods() throws IOException {
        if (!Files.isDirectory(mods())) return List.of();
        try (Stream<Path> s = Files.list(mods())) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".jar") || n.endsWith(".jar.disabled")).sorted()
                    .map(n -> new Mod(n.replace(".disabled", ""), n.endsWith(".jar"))).toList();
        }
    }

    public void setModEnabled(String jar, boolean enabled) throws IOException {
        Path on = mods().resolve(jar), off = mods().resolve(jar + ".disabled");
        if (enabled && Files.exists(off)) Files.move(off, on);
        if (!enabled && Files.exists(on)) Files.move(on, off);
    }

    public void installMod(Path jar) throws IOException {
        ensure();
        Files.copy(jar, mods().resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
    }

    // ---- save manager ----
    public List<String> listSaves() throws IOException {
        if (!Files.isDirectory(saves())) return List.of();
        try (Stream<Path> s = Files.list(saves())) {
            return s.filter(p -> Files.exists(p.resolve("level.dat"))).map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /** Zips a world (including EarthLife ledger/edits stored inside it) into backups/. */
    public Path backup(String world) throws IOException {
        Path src = saves().resolve(world);
        if (!Files.isDirectory(src)) throw new IOException("No world named " + world);
        Files.createDirectories(backups());
        Path zip = backups().resolve(world + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(zip)); Stream<Path> walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (Files.isDirectory(p) || p.getFileName().toString().equals("session.lock")) continue;
                out.putNextEntry(new ZipEntry(src.relativize(p).toString().replace('\\', '/')));
                Files.copy(p, out);
                out.closeEntry();
            }
        }
        return zip;
    }

    // ---- time settings (read by the mod when a world opens) ----
    /**
     * @param mode "REALTIME" (1 game day = 1 real day, real clock), "SCALED" (minutesPerDay real minutes per day) or "VANILLA".
     */
    public void setTime(String mode, double minutesPerDay, String zone) throws IOException {
        ensure();
        mode = mode.toUpperCase(Locale.ROOT);
        if (!Set.of("REALTIME", "SCALED", "VANILLA").contains(mode)) throw new IllegalArgumentException("mode must be realtime, speed or vanilla");
        if (minutesPerDay < 1 || minutesPerDay > 1440) throw new IllegalArgumentException("minutes per day must be 1-1440");
        Path cfg = dir.resolve("config/earthlife/earthlife.json");
        Map<String, Object> root = Files.exists(cfg) ? new LinkedHashMap<>(Json.obj(Json.parse(Files.readString(cfg)))) : new LinkedHashMap<>();
        Map<String, Object> t = new LinkedHashMap<>();
        double m = mode.equals("REALTIME") ? 1440 : mode.equals("VANILLA") ? 20 : minutesPerDay;
        t.put("mode", mode); t.put("minutesPerDay", m == Math.rint(m) ? (Object) (long) m : (Object) m);
        t.put("zone", zone); t.put("stamp", System.currentTimeMillis());
        root.put("time", t);
        AtomicFiles.write(cfg, Json.write(root).getBytes(StandardCharsets.UTF_8));
    }

    // ---- live events settings (read by the mod on world start) ----
    /** Updates one key of the "events" block: ticketmasterKey, ics (adds a URL), mlb (true/false), holidays (true/false), mlbTeams. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> setEventSetting(String key, String value) throws IOException {
        ensure();
        Path cfg = dir.resolve("config/earthlife/earthlife.json");
        Map<String, Object> root = Files.exists(cfg) ? new LinkedHashMap<>(Json.obj(Json.parse(Files.readString(cfg)))) : new LinkedHashMap<>();
        Map<String, Object> ev = new LinkedHashMap<>((Map<String, Object>) root.getOrDefault("events", Map.of()));
        switch (key) {
            case "ticketmasterKey" -> { if (!value.matches("[A-Za-z0-9]{16,64}")) throw new IllegalArgumentException("That doesn't look like a Ticketmaster API key."); ev.put(key, value); }
            case "ics" -> {
                if (!value.startsWith("https://") && !value.startsWith("http://")) throw new IllegalArgumentException("iCal feeds must be http(s) URLs.");
                List<Object> list = new ArrayList<>((List<Object>) ev.getOrDefault("ics", List.of()));
                if (!list.contains(value)) list.add(value);
                ev.put("ics", list);
            }
            case "mlb", "holidays" -> ev.put(key, Boolean.parseBoolean(value));
            case "mlbTeams" -> ev.put(key, Arrays.stream(value.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList());
            default -> throw new IllegalArgumentException("Unknown events setting " + key);
        }
        root.put("events", ev);
        AtomicFiles.write(cfg, Json.write(root).getBytes(StandardCharsets.UTF_8));
        return ev;
    }

    // ---- EarthLife world creation ----
    /**
     * Records the desired EarthLife world (name, start location, seed). V0.1: the player creates it once
     * via Create New World -> World Type "EarthLife (Voxel Earth)" using this name; after that the launcher's
     * PLAY button opens it directly with quick play. Automatic creation from this file is planned for V0.2.
     */
    public void requestWorld(String name, double lat, double lon, String place, long seed) throws IOException {
        ensure();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("worldName", name); m.put("startLat", lat); m.put("startLon", lon); m.put("startPlace", place);
        m.put("seed", seed); m.put("gamemode", "earthlife_survival");
        AtomicFiles.write(dir.resolve("config/earthlife/pending_world.json"), Json.write(m).getBytes(StandardCharsets.UTF_8));
    }
}
