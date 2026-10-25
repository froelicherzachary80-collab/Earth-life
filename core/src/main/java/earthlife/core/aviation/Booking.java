package earthlife.core.aviation;

import earthlife.core.economy.Ledger;

import java.io.IOException;
import java.util.*;

/**
 * Flight schedule, seat maps, LifeCoins pricing and ticket purchase.
 * Purchase is atomic with respect to the ledger: the seat is only assigned if the debit succeeds,
 * and the ledger reference "ticket#<flight>#<player>" makes double-purchase impossible.
 */
public final class Booking {
    public enum Fare { REGIONAL, DOMESTIC, INTERNATIONAL }

    public record PricingConfig(long regional, long domestic, long international, double regionalMaxKm, double multiplier) {
        public static PricingConfig defaults() { return new PricingConfig(100, 400, 1000, 500, 1.0); }
    }

    /** A scheduled departure. {@code departureGameDay}/{@code departureTimeTicks} use Minecraft day time (0-23999). */
    public record Flight(String id, String number, Airport from, Airport to, AircraftType type, long departureGameDay, int departureTimeTicks) {
        public double distanceKm() { return from.ref().distanceTo(to.ref()) / 1000; }
    }

    public record BoardingPass(String flightId, String flightNumber, String player, String seat, String from, String to,
                               long priceLC, String code, String gate, long ledgerSeq) {}

    private final Ledger ledger;
    private final PricingConfig pricing;
    private final Map<String, Flight> flights = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> seatsTaken = new HashMap<>();   // flightId -> seat -> player
    private final Map<String, BoardingPass> passes = new HashMap<>();             // flightId|player -> pass

    public Booking(Ledger ledger, PricingConfig pricing) { this.ledger = ledger; this.pricing = pricing; }

    public Fare fareFor(Flight f) {
        if (!Objects.equals(f.from().country(), f.to().country())) return Fare.INTERNATIONAL;
        return f.distanceKm() <= pricing.regionalMaxKm() ? Fare.REGIONAL : Fare.DOMESTIC;
    }

    public long priceFor(Flight f) {
        long base = switch (fareFor(f)) {
            case REGIONAL -> pricing.regional();
            case DOMESTIC -> pricing.domestic();
            case INTERNATIONAL -> pricing.international();
        };
        // Within a fare band, longer flights cost up to 50% more (domestic band ~500..4500 km).
        double stretch = switch (fareFor(f)) {
            case REGIONAL -> f.distanceKm() / pricing.regionalMaxKm();
            case DOMESTIC -> Math.min(1, (f.distanceKm() - pricing.regionalMaxKm()) / 4000);
            case INTERNATIONAL -> Math.min(1, f.distanceKm() / 12000);
        };
        return Math.round(base * (1 + 0.5 * Math.max(0, stretch)) * pricing.multiplier());
    }

    public void schedule(Flight f) { flights.put(f.id(), f); }

    public List<Flight> search(String fromIcao, String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return flights.values().stream()
                .filter(f -> fromIcao == null || f.from().icao().equalsIgnoreCase(fromIcao))
                .filter(f -> q.isEmpty() || f.to().icao().toLowerCase(Locale.ROOT).contains(q)
                        || (f.to().iata() != null && f.to().iata().toLowerCase(Locale.ROOT).contains(q))
                        || f.to().name().toLowerCase(Locale.ROOT).contains(q))
                .toList();
    }

    public Flight flight(String id) { return flights.get(id); }

    public List<String> allSeats(Flight f) {
        List<String> out = new ArrayList<>();
        for (int r = 1; r <= f.type().rows(); r++) for (char c : f.type().seatLetters().toCharArray()) out.add(r + "" + c);
        return out;
    }

    public synchronized List<String> availableSeats(Flight f) {
        Map<String, String> taken = seatsTaken.getOrDefault(f.id(), Map.of());
        return allSeats(f).stream().filter(s -> !taken.containsKey(s)).toList();
    }

    public synchronized BoardingPass purchase(String player, String flightId, String seat, long gameTick)
            throws Ledger.InsufficientFunds, IOException {
        Flight f = flights.get(flightId);
        if (f == null) throw new IllegalArgumentException("No such flight " + flightId);
        BoardingPass existing = passes.get(flightId + "|" + player);
        if (existing != null) return existing;                      // idempotent re-click
        if (!allSeats(f).contains(seat)) throw new IllegalArgumentException("Seat " + seat + " doesn't exist on this aircraft");
        Map<String, String> taken = seatsTaken.computeIfAbsent(flightId, k -> new HashMap<>());
        if (taken.containsKey(seat)) throw new IllegalStateException("Seat " + seat + " is taken");
        long price = priceFor(f);
        Ledger.Entry e;
        try {
            e = ledger.debit(player, price, "TICKET", "ticket#" + flightId + "#" + player, f.number() + " " + f.from().icao() + "->" + f.to().icao() + " seat " + seat, gameTick);
        } catch (Ledger.DuplicateReference d) {
            throw new IllegalStateException("Ticket already purchased for this flight (ledger)", d);
        }
        taken.put(seat, player);
        String code = String.format("%s-%06X", f.number(), (Objects.hash(flightId, player, seat, e.seq()) & 0xFFFFFF));
        BoardingPass bp = new BoardingPass(flightId, f.number(), player, seat, f.from().icao(), f.to().icao(), price, code, "A1", e.seq());
        passes.put(flightId + "|" + player, bp);
        return bp;
    }

    /**
     * Restores seat assignments and boarding passes after a restart. The ledger is the source of truth:
     * every successful purchase left a TICKET entry whose memo ends with " seat <id>".
     */
    public synchronized int restoreFromLedger() {
        int n = 0;
        for (Ledger.Entry e : ledger.all()) {
            if (!"TICKET".equals(e.type()) || !e.reference().startsWith("ticket#")) continue;
            String rest = e.reference().substring("ticket#".length());
            String player = e.player();
            if (!rest.endsWith("#" + player)) continue;
            String flightId = rest.substring(0, rest.length() - player.length() - 1);
            Flight f = flights.get(flightId);
            int at = e.memo().lastIndexOf(" seat ");
            if (f == null || at < 0) continue;
            String seat = e.memo().substring(at + 6);
            seatsTaken.computeIfAbsent(flightId, k -> new HashMap<>()).put(seat, player);
            String code = String.format("%s-%06X", f.number(), (Objects.hash(flightId, player, seat, e.seq()) & 0xFFFFFF));
            passes.put(flightId + "|" + player, new BoardingPass(flightId, f.number(), player, seat, f.from().icao(), f.to().icao(), -e.amount(), code, "A1", e.seq()));
            n++;
        }
        return n;
    }

    /** Gate agent / airport-employee check: valid pass for this flight? */
    public synchronized Optional<BoardingPass> verify(String flightId, String player) {
        return Optional.ofNullable(passes.get(flightId + "|" + player));
    }
}
