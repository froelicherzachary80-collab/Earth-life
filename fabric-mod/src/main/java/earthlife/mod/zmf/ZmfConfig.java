package earthlife.mod.zmf;

import earthlife.core.util.Json;
import earthlife.mod.EarthLifeMod;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Server-side ZMF Cinema settings (config/zmf-cinema.json; on a dedicated server this is the server's file, in
 * singleplayer your own). Created with defaults on first start.
 */
public final class ZmfConfig {
    public String rarity = "COMMON";                 // VERY_COMMON ~700 blocks apart, COMMON ~1,100, UNCOMMON ~1,800, RARE ~3,000, OFF
    public boolean naturalGeneration = true;         // multiplexes appear by themselves while exploring ordinary worlds
    public boolean naturalInEarthWorlds = false;     // Earth worlds already have their real (OpenStreetMap) theaters
    public int ticketEmeralds = 1;                   // 2D ticket (0 = free)
    public int premiumEmeralds = 2;                  // 3D, 4DX, IMAX, ScreenX tickets
    public int snackEmeralds = 1;                    // each concession item
    public boolean freeInCreative = true;            // creative/spectator players never pay
    public boolean ticketsRequired = true;           // the door scans tickets (survival players need one to enter a screening)
    public String privateShowings = "singleplayer";  // who may start their own film in any room: everyone | singleplayer | ops
    public int minYear = 2000;                       // films assigned by the scheduler: release year from (to the current year + 1)
    public boolean dailyRotation = true;             // a new program every day
    public boolean anyoneCanShareLibrary = false;    // let any player add their library's titles to the schedules (else ops / the singleplayer host)
    public int buildBlocksPerTick = 14000;           // building speed (higher = faster but can stutter)
    public int buildDistance = 176;                  // a planned multiplex is built when a player comes this close

    private static ZmfConfig current;

    public static ZmfConfig get() { if (current == null) current = load(); return current; }

    static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("zmf-cinema.json"); }

    public static synchronized ZmfConfig load() {
        ZmfConfig c = new ZmfConfig();
        try {
            if (Files.exists(file())) {
                var m = Json.obj(Json.parse(Files.readString(file())));
                c.rarity = str(m, "rarity", c.rarity);
                c.naturalGeneration = bool(m, "naturalGeneration", c.naturalGeneration);
                c.naturalInEarthWorlds = bool(m, "naturalInEarthWorlds", c.naturalInEarthWorlds);
                c.ticketEmeralds = num(m, "ticketEmeralds", c.ticketEmeralds);
                c.premiumEmeralds = num(m, "premiumEmeralds", c.premiumEmeralds);
                c.snackEmeralds = num(m, "snackEmeralds", c.snackEmeralds);
                c.freeInCreative = bool(m, "freeInCreative", c.freeInCreative);
                c.ticketsRequired = bool(m, "ticketsRequired", c.ticketsRequired);
                c.privateShowings = str(m, "privateShowings", c.privateShowings);
                c.minYear = num(m, "minYear", c.minYear);
                c.dailyRotation = bool(m, "dailyRotation", c.dailyRotation);
                c.anyoneCanShareLibrary = bool(m, "anyoneCanShareLibrary", c.anyoneCanShareLibrary);
                c.buildBlocksPerTick = Math.max(500, num(m, "buildBlocksPerTick", c.buildBlocksPerTick));
                c.buildDistance = Math.max(64, num(m, "buildDistance", c.buildDistance));
            }
            c.save();
        } catch (Exception e) { EarthLifeMod.LOG.warn("ZMF Cinema: config/zmf-cinema.json unreadable ({}), using defaults", e.toString()); }
        current = c;
        return c;
    }

    public synchronized void save() {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rarity", rarity); m.put("naturalGeneration", naturalGeneration); m.put("naturalInEarthWorlds", naturalInEarthWorlds);
            m.put("ticketEmeralds", (long) ticketEmeralds); m.put("premiumEmeralds", (long) premiumEmeralds); m.put("snackEmeralds", (long) snackEmeralds);
            m.put("freeInCreative", freeInCreative); m.put("ticketsRequired", ticketsRequired); m.put("privateShowings", privateShowings);
            m.put("minYear", (long) minYear); m.put("dailyRotation", dailyRotation); m.put("anyoneCanShareLibrary", anyoneCanShareLibrary);
            m.put("buildBlocksPerTick", (long) buildBlocksPerTick); m.put("buildDistance", (long) buildDistance);
            Files.createDirectories(file().getParent());
            Files.writeString(file(), Json.write(m));
        } catch (Exception ignored) {}
    }

    static String str(Map<String, Object> m, String k, String d) { Object v = m.get(k); return v == null ? d : v.toString(); }
    static boolean bool(Map<String, Object> m, String k, boolean d) { Object v = m.get(k); return v instanceof Boolean b ? b : d; }
    static int num(Map<String, Object> m, String k, int d) { Object v = m.get(k); return v instanceof Number n ? n.intValue() : d; }
}
