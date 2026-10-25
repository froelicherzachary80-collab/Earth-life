package earthlife.launcher;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * EarthLife launcher entry point.
 *   java -jar earthlife-launcher.jar            -> GUI
 *   java -jar earthlife-launcher.jar login      -> device-code sign-in
 *   java -jar earthlife-launcher.jar play       -> sign in (cached), install/verify, launch EarthLife world
 *   java -jar earthlife-launcher.jar repair     -> re-verify every game file by SHA-1
 *   java -jar earthlife-launcher.jar mods | saves | backup <world> | newworld
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        Path root = System.getenv("EARTHLIFE_HOME") != null ? Path.of(System.getenv("EARTHLIFE_HOME")) : Launcher.defaultRoot();
        if (args.length == 0 && !java.awt.GraphicsEnvironment.isHeadless()) { LauncherUI.open(root); return; }
        Launcher l = new Launcher(root, System.out::println);
        String cmd = args.length == 0 ? "help" : args[0];
        try { run(l, cmd, args); }
        catch (Exception e) { System.err.println("EarthLife: " + e.getMessage()); System.exit(1); }
    }

    private static void run(Launcher l, String cmd, String[] args) throws Exception {
        switch (cmd) {
            case "login" -> l.signIn(System.out::println);
            case "repair", "install" -> { l.signIn(System.out::println); l.install(); }
            case "play" -> {
                l.signIn(System.out::println);
                var p = l.install();
                var saves = l.instance.listSaves();
                String world = saves.contains("EarthLife Williamson") ? "EarthLife Williamson" : null;
                if (world == null) l.instance.requestWorld("EarthLife Williamson", 43.2237, -77.1861, "Williamson, NY", 20261025L);
                Process proc = l.launch(p, world);
                System.out.println("Minecraft started (pid " + proc.pid() + "). Log: " + l.instance.dir.resolve("latest-launch.log"));
            }
            case "newworld" -> l.instance.requestWorld(args.length > 1 ? args[1] : "EarthLife Williamson", 43.2237, -77.1861, "Williamson, NY", System.nanoTime());
            case "mods" -> l.instance.listMods().forEach(m -> System.out.println((m.enabled() ? "[on]  " : "[off] ") + m.file()));
            case "saves" -> l.instance.listSaves().forEach(System.out::println);
            case "backup" -> System.out.println("Backup written to " + l.instance.backup(String.join(" ", Arrays.copyOfRange(args, 1, args.length))));
            case "logout" -> l.signOut();
            case "events" -> {
                if (args.length < 3) throw new IllegalArgumentException("events ticketmaster <apiKey> | events ics <url> | events mlb on|off | events teams \"Rochester Red Wings,Syracuse Mets\" | events holidays on|off");
                String v = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                var ev = switch (args[1]) {
                    case "ticketmaster" -> l.instance.setEventSetting("ticketmasterKey", v);
                    case "ics" -> l.instance.setEventSetting("ics", v);
                    case "mlb" -> l.instance.setEventSetting("mlb", String.valueOf(v.equals("on")));
                    case "holidays" -> l.instance.setEventSetting("holidays", String.valueOf(v.equals("on")));
                    case "teams" -> l.instance.setEventSetting("mlbTeams", v);
                    default -> throw new IllegalArgumentException("unknown events option " + args[1]);
                };
                System.out.println("Live events settings saved (applies when the world opens): " + ev.keySet());
            }
            case "time" -> {
                String m = args.length > 1 ? args[1] : "";
                switch (m) {
                    case "realtime" -> l.instance.setTime("REALTIME", 1440, java.time.ZoneId.systemDefault().getId());
                    case "vanilla" -> l.instance.setTime("VANILLA", 20, java.time.ZoneId.systemDefault().getId());
                    case "speed" -> l.instance.setTime("SCALED", Double.parseDouble(args[2]), java.time.ZoneId.systemDefault().getId());
                    default -> throw new IllegalArgumentException("time realtime | time speed <minutes per day> | time vanilla");
                }
                System.out.println("Time setting saved; it applies the next time you open your world.");
            }
            default -> System.out.println("Commands: login | play | repair | newworld [name] | mods | saves | backup <world> | time realtime|speed <min>|vanilla | events ... | logout   (no args = GUI)");
        }
    }
}
