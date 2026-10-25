package earthlife.core.economy;

import earthlife.core.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Persistent, append-only LifeCoins transaction ledger. LifeCoins (LC) are a fictional in-game
 * currency: not Minecoins, not purchasable, not redeemable for real money.
 *
 * Every entry is a JSON line chained by SHA-256 to the previous one, so edits or truncation in the
 * middle of the file are detected on load. Each entry carries an idempotency key ("reference");
 * posting the same reference twice is rejected, which is what makes reward claims and ticket
 * purchases duplicate-safe even if a packet or click is repeated.
 */
public final class Ledger {
    public record Entry(long seq, String player, long amount, long balanceAfter, String type, String reference, String memo, long gameTick, String prevHash, String hash) {}

    public static final class InsufficientFunds extends Exception {
        public InsufficientFunds(long need, long have) { super("Need " + need + " LC, have " + have + " LC"); }
    }
    public static final class DuplicateReference extends Exception {
        public DuplicateReference(String ref) { super("Already posted: " + ref); }
    }
    public static final class CorruptLedger extends IOException {
        public CorruptLedger(String m) { super(m); }
    }

    private final Path file;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Long> balances = new HashMap<>();
    private final Set<String> references = new HashSet<>();
    private String lastHash = "GENESIS";

    private Ledger(Path file) { this.file = file; }

    public static Ledger open(Path file) throws IOException {
        Ledger l = new Ledger(file);
        if (Files.exists(file)) {
            int lineNo = 0;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                lineNo++;
                if (line.isBlank()) continue;
                Map<String, Object> m;
                try { m = Json.obj(Json.parse(line)); } catch (RuntimeException e) {
                    throw new CorruptLedger("Unreadable ledger line " + lineNo + " (possibly an interrupted write)");
                }
                Entry e = new Entry(((Number) m.get("seq")).longValue(), (String) m.get("player"), ((Number) m.get("amount")).longValue(),
                        ((Number) m.get("balanceAfter")).longValue(), (String) m.get("type"), (String) m.get("reference"),
                        (String) m.get("memo"), ((Number) m.get("gameTick")).longValue(), (String) m.get("prevHash"), (String) m.get("hash"));
                if (!e.prevHash().equals(l.lastHash)) throw new CorruptLedger("Hash chain broken at line " + lineNo);
                if (!e.hash().equals(hashOf(e))) throw new CorruptLedger("Entry tampered at line " + lineNo);
                long bal = l.balances.getOrDefault(e.player(), 0L) + e.amount();
                if (bal != e.balanceAfter() || bal < 0) throw new CorruptLedger("Balance mismatch at line " + lineNo);
                l.apply(e);
            }
        }
        return l;
    }

    private void apply(Entry e) {
        entries.add(e);
        balances.put(e.player(), e.balanceAfter());
        references.add(e.player() + "|" + e.reference());
        lastHash = e.hash();
    }

    public synchronized long balance(String player) { return balances.getOrDefault(player, 0L); }
    public synchronized boolean hasReference(String player, String reference) { return references.contains(player + "|" + reference); }

    public synchronized List<Entry> all() { return List.copyOf(entries); }

    public synchronized List<Entry> history(String player) {
        return entries.stream().filter(e -> e.player().equals(player)).toList();
    }

    public synchronized Entry credit(String player, long amount, String type, String reference, String memo, long gameTick) throws DuplicateReference, IOException {
        if (amount <= 0) throw new IllegalArgumentException("credit must be positive");
        try { return post(player, amount, type, reference, memo, gameTick); }
        catch (InsufficientFunds e) { throw new AssertionError(e); }
    }

    public synchronized Entry debit(String player, long amount, String type, String reference, String memo, long gameTick) throws InsufficientFunds, DuplicateReference, IOException {
        if (amount <= 0) throw new IllegalArgumentException("debit must be positive");
        return post(player, -amount, type, reference, memo, gameTick);
    }

    private Entry post(String player, long amount, String type, String reference, String memo, long gameTick) throws InsufficientFunds, DuplicateReference, IOException {
        Objects.requireNonNull(reference, "reference");
        if (references.contains(player + "|" + reference)) throw new DuplicateReference(reference);
        long bal = balance(player) + amount;
        if (bal < 0) throw new InsufficientFunds(-amount, balance(player));
        Entry draft = new Entry(entries.size() + 1, player, amount, bal, type, reference, memo == null ? "" : memo, gameTick, lastHash, "");
        Entry e = new Entry(draft.seq(), player, amount, bal, type, reference, draft.memo(), gameTick, lastHash, hashOf(draft));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", e.seq()); m.put("player", e.player()); m.put("amount", e.amount()); m.put("balanceAfter", e.balanceAfter());
        m.put("type", e.type()); m.put("reference", e.reference()); m.put("memo", e.memo()); m.put("gameTick", e.gameTick());
        m.put("prevHash", e.prevHash()); m.put("hash", e.hash());
        Files.createDirectories(file.toAbsolutePath().getParent());
        // Append + fsync before updating memory: a crash can lose the newest entry but never commit one silently.
        try (var ch = java.nio.channels.FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(java.nio.ByteBuffer.wrap((Json.write(m) + "\n").getBytes(StandardCharsets.UTF_8)));
            ch.force(true);
        }
        apply(e);
        return e;
    }

    static String hashOf(Entry e) {
        String canonical = e.seq() + "|" + e.player() + "|" + e.amount() + "|" + e.balanceAfter() + "|" + e.type() + "|" + e.reference()
                + "|" + e.memo() + "|" + e.gameTick() + "|" + e.prevHash();
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
