package earthlife.core.jobs;

import earthlife.core.economy.Ledger;
import earthlife.core.time.GameClock;
import earthlife.core.util.Json;
import earthlife.core.world.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Employment, shifts, tasks and wages.
 *  - One job per player; licences (driver, pilot) gate some careers. Licences are earned in-game (driving test,
 *    flight school), recorded here.
 *  - Clock in only during the shift window and at the workplace (the game checks location); auto clock-out at shift end.
 *  - Tasks are recorded with a unique reference (the spill, package, passenger or stop that was handled) so the same
 *    task can't be counted twice, and only task kinds that belong to the job count.
 *  - Pay = hours on shift x hourly rate + tasks x task rate, but hourly pay requires at least one task per two hours
 *    worked (no AFK wages). Paid to the LifeCoins ledger with a per-shift reference: never twice.
 */
public final class JobService {
    public record Employment(String player, JobType job, String employer, long hiredDay) {}
    public record Shift(long startTime, Set<String> tasks) {}
    public record Payslip(long hoursTicks, int tasks, long hourlyPay, long taskPay, long total, boolean hourlyWithheld) {}
    public record Task(String id, JobType job, String kind, String target, long reward) {}

    public static final class JobException extends Exception { public JobException(String m) { super(m); } }

    private final Ledger ledger;
    private final Path file;
    private final Map<String, Employment> jobs = new HashMap<>();
    private final Map<String, Shift> shifts = new HashMap<>();
    private final Map<String, Set<String>> licences = new HashMap<>();
    private final Map<String, Long> shiftCounter = new HashMap<>();
    private final Map<String, Long> cropPrices = new LinkedHashMap<>(Map.of(
            "minecraft:wheat", 4L, "minecraft:carrot", 3L, "minecraft:potato", 3L, "minecraft:apple", 6L,
            "minecraft:beetroot", 4L, "minecraft:pumpkin", 12L, "minecraft:melon_slice", 2L, "minecraft:sweet_berries", 2L));

    public JobService(Ledger ledger, Path file) throws IOException { this.ledger = ledger; this.file = file; load(); }

    public synchronized Optional<Employment> employment(String p) { return Optional.ofNullable(jobs.get(p)); }
    public synchronized Optional<Shift> shift(String p) { return Optional.ofNullable(shifts.get(p)); }
    public synchronized Set<String> licences(String p) { return Set.copyOf(licences.getOrDefault(p, Set.of())); }
    public Map<String, Long> cropPrices() { return Collections.unmodifiableMap(cropPrices); }

    public synchronized void grantLicence(String p, String licence) throws IOException {
        licences.computeIfAbsent(p, k -> new TreeSet<>()).add(licence); save();
    }

    public synchronized Employment hire(String p, JobType job, String employer, long day) throws JobException, IOException {
        if (jobs.containsKey(p)) throw new JobException("You already work as " + jobs.get(p).job().title + ". Quit first.");
        if (job.requirement != null && !licences.getOrDefault(p, Set.of()).contains(job.requirement))
            throw new JobException(job.title + " requires " + job.requirement.replace("license:", "a ") + " licence.");
        Employment e = new Employment(p, job, employer, day);
        jobs.put(p, e); save();
        return e;
    }

    /** Quitting while on shift pays out the shift first. */
    public synchronized Optional<Payslip> quit(String p, long dayTime, long tick) throws IOException {
        Optional<Payslip> pay = shifts.containsKey(p) ? Optional.of(clockOut(p, dayTime, tick)) : Optional.empty();
        jobs.remove(p); save();
        return pay;
    }

    public static boolean inWindow(JobType j, long dayTime) {
        if (!j.hasShifts()) return false;
        int h = GameClock.hour(dayTime);
        int early = j.shiftStart - 1;   // may clock in up to one game-hour early
        return h >= early && h < j.shiftEnd;
    }

    public synchronized Shift clockIn(String p, long dayTime, boolean atWorkplace) throws JobException, IOException {
        Employment e = jobs.get(p);
        if (e == null) throw new JobException("You don't have a job. Visit an employer to apply.");
        if (!e.job().hasShifts()) throw new JobException(e.job().title + "s work their own hours: no clocking in.");
        if (shifts.containsKey(p)) throw new JobException("Already clocked in.");
        if (!atWorkplace) throw new JobException("Clock in at your workplace (" + e.employer() + ").");
        if (!inWindow(e.job(), dayTime))
            throw new JobException(String.format("Your shift is %02d:00-%02d:00.", e.job().shiftStart, e.job().shiftEnd));
        Shift s = new Shift(dayTime, new LinkedHashSet<>());
        shifts.put(p, s); save();
        return s;
    }

    /** @return true if the task counted. */
    public synchronized boolean recordTask(String p, String kind, String ref) throws IOException {
        Employment e = jobs.get(p);
        Shift s = shifts.get(p);
        if (e == null || s == null || !e.job().taskKinds.contains(kind)) return false;
        boolean added = s.tasks().add(kind + ":" + ref);
        if (added) save();
        return added;
    }

    public synchronized Payslip clockOut(String p, long dayTime, long tick) throws IOException {
        Employment e = jobs.get(p);
        Shift s = shifts.remove(p);
        if (e == null || s == null) return new Payslip(0, 0, 0, 0, 0, false);
        long shiftEndTime = endOfShift(e.job(), s.startTime());
        long worked = Math.max(0, Math.min(dayTime, shiftEndTime) - s.startTime());
        int tasks = s.tasks().size();
        long hourly = worked * e.job().perHour / 1000;
        boolean withheld = tasks < Math.max(1, worked / 2000);
        if (withheld) hourly = 0;
        long taskPay = tasks * e.job().perTask;
        long total = hourly + taskPay;
        long n = shiftCounter.merge(p, 1L, Long::sum);
        if (total > 0) {
            try {
                ledger.credit(p, total, "WAGE", "wage#" + p + "#" + n + "#" + s.startTime(),
                        e.job().title + " shift: " + (worked / 1000.0) + " h, " + tasks + " tasks", tick);
            } catch (Ledger.DuplicateReference d) { /* already paid (crash recovery) */ }
        }
        save();
        return new Payslip(worked, tasks, hourly, taskPay, total, withheld);
    }

    static long endOfShift(JobType j, long start) {
        long dayStart = start - Math.floorMod(start, GameClock.TICKS_PER_DAY);
        long end = dayStart + GameClock.ticksOfDay(java.time.LocalTime.of(j.shiftEnd % 24, 0));
        while (end <= start) end += GameClock.TICKS_PER_DAY;
        return end;
    }

    /** Call regularly: ends shifts whose window has passed. Returns players who were clocked out with their payslips. */
    public synchronized Map<String, Payslip> autoClockOut(long dayTime, long tick) throws IOException {
        Map<String, Payslip> out = new LinkedHashMap<>();
        for (String p : new ArrayList<>(shifts.keySet())) {
            Employment e = jobs.get(p);
            if (e == null || dayTime >= endOfShift(e.job(), shifts.get(p).startTime())) out.put(p, clockOut(p, dayTime, tick));
        }
        return out;
    }

    /** Farmers (and anyone) can sell crops at a market stall. {@code saleId} must be unique per sale action. */
    public synchronized long sell(String p, String item, int count, String saleId, long tick) throws IOException {
        Long price = cropPrices.get(item);
        if (price == null || count <= 0) return 0;
        long amount = price * count;
        try { ledger.credit(p, amount, "SALE", "sale#" + saleId, count + "x " + item, tick); }
        catch (Ledger.DuplicateReference d) { return 0; }
        return amount;
    }

    /**
     * Deterministic task list for a workplace and day (spills to clean, packages to deliver, shelves to restock ...).
     * {@code targets} are workplace marker names / addresses supplied by the game.
     */
    public static List<Task> tasksFor(JobType job, String employer, long day, List<String> targets, int count) {
        List<Task> out = new ArrayList<>();
        if (targets.isEmpty() || job.taskKinds.isEmpty()) return out;
        Random r = new Random(Objects.hash(job, employer, day));
        String kind = job.taskKinds.stream().sorted().findFirst().orElseThrow();
        if (job == JobType.STORE_CLERK) kind = "restock";
        if (job == JobType.GATE_AGENT) kind = "board";
        for (int i = 0; i < count; i++) {
            String target = targets.get(r.nextInt(targets.size()));
            out.add(new Task(employer + "/" + day + "/" + kind + "/" + i, job, kind, target, job.perTask));
        }
        return out;
    }

    // ---- persistence ----
    private void load() throws IOException {
        if (!Files.exists(file)) return;
        var root = Json.obj(Json.parse(Files.readString(file)));
        for (Object o : Json.arr(root.get("jobs"))) {
            var m = Json.obj(o);
            jobs.put((String) m.get("player"), new Employment((String) m.get("player"), JobType.valueOf((String) m.get("job")),
                    (String) m.get("employer"), ((Number) m.get("hiredDay")).longValue()));
        }
        for (Object o : Json.arr(root.get("shifts"))) {
            var m = Json.obj(o);
            Set<String> t = new LinkedHashSet<>();
            for (Object x : Json.arr(m.get("tasks"))) t.add((String) x);
            shifts.put((String) m.get("player"), new Shift(((Number) m.get("start")).longValue(), t));
        }
        var lic = Json.obj(root.get("licences"));
        if (lic != null) for (var e : lic.entrySet()) { Set<String> s = new TreeSet<>(); for (Object x : Json.arr(e.getValue())) s.add((String) x); licences.put(e.getKey(), s); }
        var sc = Json.obj(root.get("shiftCounter"));
        if (sc != null) for (var e : sc.entrySet()) shiftCounter.put(e.getKey(), ((Number) e.getValue()).longValue());
    }

    private void save() throws IOException {
        List<Object> js = new ArrayList<>();
        for (Employment e : jobs.values()) js.add(Map.of("player", e.player(), "job", e.job().name(), "employer", e.employer(), "hiredDay", e.hiredDay()));
        List<Object> ss = new ArrayList<>();
        for (var e : shifts.entrySet()) ss.add(Map.of("player", e.getKey(), "start", e.getValue().startTime(), "tasks", new ArrayList<>(e.getValue().tasks())));
        Map<String, Object> lic = new TreeMap<>();
        for (var e : licences.entrySet()) lic.put(e.getKey(), new ArrayList<>(e.getValue()));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("jobs", js); root.put("shifts", ss); root.put("licences", lic); root.put("shiftCounter", new TreeMap<>(shiftCounter));
        AtomicFiles.write(file, Json.write(root).getBytes(StandardCharsets.UTF_8));
    }
}
