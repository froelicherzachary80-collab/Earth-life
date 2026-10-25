package earthlife.launcher;

import earthlife.launcher.mc.VersionProfile;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.nio.file.Path;

/** Swing launcher window: blocky, dark, Minecraft-inspired. All network work runs off the UI thread. */
public final class LauncherUI {
    static final Color BG = new Color(0x1E1F22), PANEL = new Color(0x2B2D31), GRASS = new Color(0x5B8C32), DIRT = new Color(0x6B4A2B), TEXT = new Color(0xEDEDED);

    private final JTextArea log = new JTextArea();
    private final JLabel account = new JLabel("Not signed in");
    private final JButton play = button("PLAY", GRASS);
    private Launcher launcher;
    private VersionProfile profile;

    public static void open(Path root) {
        SwingUtilities.invokeLater(() -> new LauncherUI().build(root));
    }

    private static JButton button(String text, Color c) {
        JButton b = new JButton(text);
        b.setBackground(c); b.setForeground(Color.WHITE); b.setFocusPainted(false);
        b.setFont(new Font(Font.MONOSPACED, Font.BOLD, 16));
        b.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Color.BLACK, 3), new EmptyBorder(10, 18, 10, 18)));
        return b;
    }

    private void build(Path root) {
        try { launcher = new Launcher(root, this::append); } catch (Exception e) { JOptionPane.showMessageDialog(null, e.getMessage()); return; }
        JFrame f = new JFrame("EARTHLIFE — Reality Simulator");
        f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        f.getContentPane().setBackground(BG);
        f.setLayout(new BorderLayout(12, 12));

        JLabel title = new JLabel("EARTHLIFE", SwingConstants.CENTER);
        title.setFont(new Font(Font.MONOSPACED, Font.BOLD, 44)); title.setForeground(TEXT);
        JLabel sub = new JLabel("Reality Simulator · Minecraft Java " + launcher.gameVersion() + " · Fabric", SwingConstants.CENTER);
        sub.setForeground(new Color(0xA0A0A0));
        JPanel header = new JPanel(new GridLayout(2, 1)); header.setBackground(BG); header.setBorder(new EmptyBorder(18, 0, 0, 0));
        header.add(title); header.add(sub);
        f.add(header, BorderLayout.NORTH);

        JPanel side = new JPanel(new GridLayout(0, 1, 0, 8)); side.setBackground(BG); side.setBorder(new EmptyBorder(0, 16, 0, 0));
        JButton signIn = button("Sign in & Play (Minecraft Launcher)", new Color(0x2F6FB0));
        JButton repair = button("Install / Repair", DIRT);
        JButton newWorld = button("New EarthLife World", DIRT);
        JButton mods = button("Mods", PANEL), saves = button("Saves", PANEL), settings = button("Settings", PANEL);
        account.setForeground(TEXT);
        for (JComponent c : new JComponent[]{account, signIn, repair, newWorld, mods, saves, settings}) side.add(c);
        f.add(side, BorderLayout.WEST);

        log.setEditable(false); log.setBackground(PANEL); log.setForeground(TEXT); log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        log.setLineWrap(true);
        f.add(new JScrollPane(log), BorderLayout.CENTER);

        JPanel bottom = new JPanel(); bottom.setBackground(BG); bottom.add(play);
        play.setPreferredSize(new Dimension(320, 64));
        f.add(bottom, BorderLayout.SOUTH);

        signIn.addActionListener(e -> bg(() -> {
            launcher.playWithOfficialLauncher();
            SwingUtilities.invokeLater(() -> account.setText("Using the Minecraft Launcher"));
        }));
        repair.addActionListener(e -> bg(() -> profile = launcher.install()));
        newWorld.addActionListener(e -> bg(() -> {
            launcher.instance.requestWorld("EarthLife Williamson", 43.2237, -77.1861, "Williamson, NY", System.nanoTime());
            append("World request saved. It will be created on next launch (Williamson, NY).");
        }));
        mods.addActionListener(e -> bg(() -> {
            var list = launcher.instance.listMods();
            append(list.isEmpty() ? "No mods installed." : "Mods:");
            list.forEach(m -> append((m.enabled() ? "  [on]  " : "  [off] ") + m.file()));
        }));
        saves.addActionListener(e -> bg(() -> {
            var list = launcher.instance.listSaves();
            append(list.isEmpty() ? "No worlds yet." : "Worlds: " + String.join(", ", list));
            for (String w : list) append("  backup -> " + launcher.instance.backup(w));
        }));
        JButton time = button("Day length", PANEL);
        side.add(time);
        time.addActionListener(e -> {
            String[] opts = {"Real time (1 day = 1 day)", "2 hours", "1 hour", "Vanilla (20 min)"};
            String pick = (String) JOptionPane.showInputDialog(f, "How long should one Minecraft day last?", "Time", JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
            if (pick == null) return;
            bg(() -> {
                String zone = java.time.ZoneId.systemDefault().getId();
                if (pick.startsWith("Real")) launcher.instance.setTime("REALTIME", 1440, zone);
                else if (pick.startsWith("2")) launcher.instance.setTime("SCALED", 120, zone);
                else if (pick.startsWith("1")) launcher.instance.setTime("SCALED", 60, zone);
                else launcher.instance.setTime("VANILLA", 20, zone);
                append("Time: " + pick + " (applies when the world opens; change it in-game with /earthlife time).");
            });
        });
        JButton events = button("Live events", PANEL);
        side.add(events);
        events.addActionListener(e -> {
            String[] opts = {"Add Ticketmaster API key (concerts & shows)", "Add a calendar (.ics) URL", "Baseball schedules on", "Baseball schedules off"};
            String pick = (String) JOptionPane.showInputDialog(f, "Real events appear in game at the real place and time.\nHolidays and Rochester Red Wings games work out of the box.",
                    "Live events", JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
            if (pick == null) return;
            String value = pick.startsWith("Add") ? JOptionPane.showInputDialog(f, pick.startsWith("Add Ticket")
                    ? "Free key from developer.ticketmaster.com (Consumer Key):" : "Public calendar URL (https://...ics):") : null;
            if (pick.startsWith("Add") && (value == null || value.isBlank())) return;
            bg(() -> {
                if (pick.startsWith("Add Ticket")) launcher.instance.setEventSetting("ticketmasterKey", value.trim());
                else if (pick.startsWith("Add a cal")) launcher.instance.setEventSetting("ics", value.trim());
                else launcher.instance.setEventSetting("mlb", String.valueOf(pick.endsWith("on")));
                append("Live events: saved (" + pick + ").");
            });
        });
        settings.addActionListener(e -> JOptionPane.showMessageDialog(f,
                "Settings live in " + launcher.root.resolve("launcher.json") + "\n(gameVersion, fabricLoader, memoryMb, msaClientId)"));
        play.addActionListener(e -> bg(() -> {
            String world = launcher.instance.listSaves().contains("EarthLife Williamson") ? "EarthLife Williamson" : null;
            if (world == null) launcher.instance.requestWorld("EarthLife Williamson", 43.2237, -77.1861, "Williamson, NY", 20261025L);
            launcher.playWithOfficialLauncher();
        }));

        f.setSize(900, 600);
        f.setLocationRelativeTo(null);
        f.setVisible(true);
        append("Welcome to EarthLife. Press PLAY: the official Minecraft Launcher opens with an \"EarthLife\" installation ready.");
    }

    private interface Job { void run() throws Exception; }

    private void bg(Job j) {
        play.setEnabled(false);
        Thread t = new Thread(() -> {
            try { j.run(); } catch (Exception ex) { append("ERROR: " + ex.getMessage()); }
            finally { SwingUtilities.invokeLater(() -> play.setEnabled(true)); }
        }, "EarthLife-Launcher-Job");
        t.setDaemon(true);
        t.start();
    }

    private void append(String s) { SwingUtilities.invokeLater(() -> { log.append(s + "\n"); log.setCaretPosition(log.getDocument().getLength()); }); }
}
