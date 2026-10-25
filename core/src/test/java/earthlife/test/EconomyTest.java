package earthlife.test;

import earthlife.core.economy.*;

import java.nio.file.*;

import static earthlife.test.T.*;

public final class EconomyTest {
    static final long DAY_MS = 86_400_000L;
    static final long OCT25_NOON_NY = 1_761_408_000_000L + 16 * 3_600_000L; // 2025-10-25T16:00Z

    public static void testLedgerCreditDebitAndPersistence() throws Exception {
        Path f = tmpDir().resolve("ledger.jsonl");
        Ledger l = Ledger.open(f);
        l.credit("zach", 700, "REWARD_DAILY", "daily#1", "", 0);
        l.debit("zach", 100, "TICKET", "t1", "", 5);
        check(l.balance("zach") == 600, "balance");
        throwsEx(Ledger.InsufficientFunds.class, () -> l.debit("zach", 601, "TICKET", "t2", "", 6), "overdraft blocked");
        throwsEx(Ledger.DuplicateReference.class, () -> l.credit("zach", 700, "REWARD_DAILY", "daily#1", "", 7), "duplicate reference blocked");
        Ledger again = Ledger.open(f);
        check(again.balance("zach") == 600 && again.history("zach").size() == 2, "reloaded");
    }

    public static void testLedgerTamperDetected() throws Exception {
        Path f = tmpDir().resolve("ledger.jsonl");
        Ledger l = Ledger.open(f);
        l.credit("zach", 150, "REWARD_HOURLY", "hourly#1", "", 0);
        l.credit("zach", 150, "REWARD_HOURLY", "hourly#2", "", 1);
        Files.writeString(f, Files.readString(f).replaceFirst("\"amount\":150", "\"amount\":99999"));
        throwsEx(Ledger.CorruptLedger.class, () -> Ledger.open(f), "edited amount detected");
    }

    static void play(RewardService r, String p, long fromTick, long ticks) {
        for (long t = fromTick; t < fromTick + ticks; t++) {
            if (t == fromTick || t % 1200 == 0) r.markActivity(p, t); // active once a minute
            r.tick(p, t);
        }
    }

    public static void testHourlyRewardRequiresActivePlay() throws Exception {
        Path d = tmpDir();
        Ledger l = Ledger.open(d.resolve("l.jsonl"));
        RewardService r = new RewardService(l, RewardService.Config.defaults(), d.resolve("rewards.json"));
        play(r, "zach", 1, RewardService.HOURLY_TICKS - 10);
        check(r.claimHourly("zach", 1).isEmpty(), "not yet an hour");
        check(r.ticksUntilNextHourly("zach") > 0, "countdown");
        play(r, "zach", RewardService.HOURLY_TICKS - 9, 20);
        check(r.claimHourly("zach", 2).isPresent(), "hour reached");
        check(r.claimHourly("zach", 3).isEmpty(), "can't claim twice");
        check(l.balance("zach") == 150, "150 LC");
        // AFK: activity stops; ticks keep coming but don't count
        long t0 = RewardService.HOURLY_TICKS + 20;
        for (long t = t0; t < t0 + 2 * RewardService.HOURLY_TICKS; t++) r.tick("zach", t);
        check(r.hourlyAvailable("zach") == 0, "AFK earns nothing beyond 5 min grace");
        // Replayed / backwards ticks are ignored
        check(!r.tick("zach", 5), "old tick rejected");
    }

    public static void testDailyRewardResistsClockRollback() throws Exception {
        Path d = tmpDir();
        Ledger l = Ledger.open(d.resolve("l.jsonl"));
        RewardService r = new RewardService(l, RewardService.Config.defaults(), d.resolve("rewards.json"));
        check(r.claimDaily("zach", OCT25_NOON_NY, 0).isPresent(), "first daily");
        check(r.claimDaily("zach", OCT25_NOON_NY + 3_600_000, 0).isEmpty(), "same day again");
        check(r.claimDaily("zach", OCT25_NOON_NY + DAY_MS, 0).isPresent(), "next day");
        // Player sets their clock back a day then forward again "to the next day": still only counts real progress.
        check(r.claimDaily("zach", OCT25_NOON_NY, 0).isEmpty(), "rollback gives nothing");
        check(r.claimDaily("zach", OCT25_NOON_NY + DAY_MS + 1000, 0).isEmpty(), "still day 2");
        check(l.balance("zach") == 1400, "two dailies");
        // State persists across restart
        RewardService r2 = new RewardService(Ledger.open(d.resolve("l.jsonl")), RewardService.Config.defaults(), d.resolve("rewards.json"));
        check(r2.claimDaily("zach", OCT25_NOON_NY + DAY_MS + 5000, 0).isEmpty(), "restart doesn't reset daily");
    }

    public static void testWeeklyMilestone() throws Exception {
        Path d = tmpDir();
        Ledger l = Ledger.open(d.resolve("l.jsonl"));
        var cfg = new RewardService.Config(150, 700, 1500, 2, java.time.ZoneId.of("America/New_York"));
        RewardService r = new RewardService(l, cfg, d.resolve("rewards.json"));
        check(r.claimWeekly("zach", OCT25_NOON_NY, 0).isEmpty(), "no hours yet");
        play(r, "zach", 1, 2 * RewardService.HOURLY_TICKS + 5);
        check(r.claimWeekly("zach", OCT25_NOON_NY + 1000, 0).isPresent(), "2 active hours this week");
        check(r.claimWeekly("zach", OCT25_NOON_NY + 2000, 0).isEmpty(), "once per week");
    }
}
