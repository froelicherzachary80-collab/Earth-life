package earthlife.core.life;

import java.util.*;

/**
 * Guided objectives per starting scenario. Each completes when the matching thing happens in game (the client
 * reports screen opens with "/earthlife ui opened <screen>"; everything else is detected on the server).
 */
public final class Objectives {
    private Objectives() {}

    public record Objective(String id, String text, long rewardLC) {}

    static final Objective MENU = new Objective("open_menu", "Open the EarthLife menu [K]", 10);
    static final Objective MAP = new Objective("open_map", "Open your LifeMap [M] and find your town", 10);
    static final Objective NEARBY = new Objective("open_nearby", "See who's nearby [N]", 10);
    static final Objective TALK = new Objective("talk", "Talk to a resident (right-click them)", 25);
    static final Objective JOB = new Objective("get_job", "Get a job [J]", 25);
    static final Objective SHIFT = new Objective("clock_in", "Clock in for a shift at your workplace", 25);
    static final Objective TASK = new Objective("work_task", "Finish a work task", 50);
    static final Objective WALLET = new Objective("open_wallet", "Check your wallet [L]", 10);
    static final Objective HOME = new Objective("set_home", "Set your home on the LifeMap (or /earthlife home set)", 25);
    static final Objective EVENT = new Objective("event", "Join a live event (Halloween: trick-or-treat a resident)", 50);
    static final Objective FLIGHTS = new Objective("view_flights", "Look at today's flights (/earthlife flights)", 10);
    static final Objective BOOK = new Objective("book_flight", "Book a flight", 50);
    static final Objective LANDMARK = new Objective("waypoint", "Add a waypoint on the LifeMap", 25);

    public static List<Objective> forScenario(String scenario) {
        return switch (scenario) {
            case "NEW_LIFE" -> List.of(MENU, MAP, TALK, NEARBY, JOB, SHIFT, WALLET, HOME, EVENT);
            case "CAREER_STARTER" -> List.of(MENU, JOB, SHIFT, TASK, WALLET, HOME);
            case "TRAVELER" -> List.of(MAP, FLIGHTS, BOOK, LANDMARK, TALK, EVENT);
            default -> List.of();
        };
    }

    /** The next objectives to show, done ones first marked with ✔. */
    public static List<String> lines(PlayerLife p) {
        List<String> out = new ArrayList<>();
        for (Objective o : forScenario(p.scenario)) out.add((p.objectivesDone.contains(o.id) ? "✔ " : "• ") + o.text);
        return out;
    }

    public static Optional<Objective> byId(String scenario, String id) {
        return forScenario(scenario).stream().filter(o -> o.id.equals(id)).findFirst();
    }
}
