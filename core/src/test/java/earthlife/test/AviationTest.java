package earthlife.test;

import earthlife.core.aviation.*;
import earthlife.core.economy.Ledger;
import earthlife.core.geo.GeoPoint;

import java.nio.file.*;
import java.util.*;

import static earthlife.test.T.*;

public final class AviationTest {
    static Map<String, Airport> airports() throws Exception {
        Path p = Path.of(System.getProperty("earthlife.data", "../data"), "airports.json");
        return Airport.loadAll(Files.readString(p));
    }

    public static void testFullFlightSdcToRocIsContinuousAndLands() throws Exception {
        var a = airports();
        Airport sdc = a.get("KSDC"), roc = a.get("KROC");
        FlightSim sim = new FlightSim(AircraftType.HOPPER, sdc, roc);
        check(sim.telemetry().doorsOpen(), "doors open while boarding");
        sim.tick(10);
        check(sim.phase() == FlightSim.Phase.BOARDING, "doesn't move until departure");
        sim.beginDeparture();

        Set<FlightSim.Phase> seen = EnumSet.noneOf(FlightSim.Phase.class);
        GeoPoint prev = sim.telemetry().position();
        double maxAlt = 0, prevEta = Double.MAX_VALUE;
        int etaIncreases = 0;
        boolean gearUpSeen = false;
        int ticks = 0;
        while (sim.phase() != FlightSim.Phase.ARRIVED && ticks < 20 * 60 * 60) {
            sim.tick(0.05);  // one Minecraft tick
            ticks++;
            var t = sim.telemetry();
            seen.add(t.phase());
            double moved = prev.distanceTo(t.position());
            check(moved <= t.groundSpeed() * 0.05 + 0.5 + 1e-6 || moved < 12, "no teleport: moved " + moved + " m in one tick at " + t.groundSpeed() + " m/s (" + t.phase() + ")");
            check(t.altitudeAgl() > -0.5, "never underground, agl=" + t.altitudeAgl());
            if (t.phase() != FlightSim.Phase.ARRIVED) check(!t.doorsOpen(), "doors closed in motion");
            if (t.gearExtension() == 0) gearUpSeen = true;
            maxAlt = Math.max(maxAlt, t.altitudeMsl());
            if (ticks % 200 == 0) { if (t.etaSeconds() > prevEta + 20) etaIncreases++; prevEta = t.etaSeconds(); }
            prev = t.position();
        }
        var end = sim.telemetry();
        check(end.phase() == FlightSim.Phase.ARRIVED, "arrived, phase=" + end.phase() + " after " + ticks + " ticks");
        check(seen.containsAll(EnumSet.range(FlightSim.Phase.TAXI_OUT, FlightSim.Phase.TAXI_IN)), "all phases flown: " + seen);
        check(end.position().distanceTo(roc.gate()) < 1, "parked at ROC gate");
        check(end.doorsOpen() && end.gearExtension() == 1, "doors open, gear down at gate");
        check(gearUpSeen, "gear retracted in flight");
        check(maxAlt > roc.elevationM() + 300, "climbed to a real altitude: " + maxAlt);
        check(etaIncreases <= 2, "ETA roughly monotonic, jumps=" + etaIncreases);
        double minutes = ticks / 20.0 / 60;
        check(minutes > 6 && minutes < 30, "plausible block time " + minutes + " min");
        System.out.printf("    SDC->ROC: %.1f km path, cruise %.0f m MSL, %.1f min real time at 1x%n", sim.totalDistance() / 1000, sim.cruiseAltitude(), minutes);
    }

    public static void testTimeCompressionShortensFlightNotPath() throws Exception {
        var a = airports();
        FlightSim s1 = new FlightSim(AircraftType.HOPPER, a.get("KROC"), a.get("KSDC"));
        FlightSim s4 = new FlightSim(AircraftType.HOPPER, a.get("KROC"), a.get("KSDC"));
        s4.setTimeCompression(4);
        s1.beginDeparture(); s4.beginDeparture();
        int t1 = 0, t4 = 0;
        while (s1.phase() != FlightSim.Phase.ARRIVED && t1 < 200_000) { s1.tick(0.05); t1++; }
        while (s4.phase() != FlightSim.Phase.ARRIVED && t4 < 200_000) { s4.tick(0.05); t4++; }
        check(s1.phase() == FlightSim.Phase.ARRIVED && s4.phase() == FlightSim.Phase.ARRIVED, "both arrive");
        near(t1 / (double) t4, 4, 0.1, "4x compression");
        near(s1.telemetry().elapsedSeconds(), s4.telemetry().elapsedSeconds(), 1, "same simulated duration");
    }

    public static void testLongRouteFollowsGreatCircle() {
        // Synthetic far airports: JFK-ish to London-ish. Midpoint of a great circle lies well north of the rhumb line.
        Airport a = fake("KAAA", "US", 40.64, -73.78), b = fake("EGBB", "GB", 51.47, -0.45);
        FlightSim s = new FlightSim(AircraftType.HOPPER, a, b);
        GeoPoint mid = s.positionAt(s.totalDistance() / 2);
        check(mid.lat() > 51, "great-circle midpoint goes north (lat " + mid.lat() + ")");
        check(Math.abs(s.totalDistance() - a.ref().distanceTo(b.ref())) < 30_000, "path length ~ great circle distance");
    }

    static Airport fake(String icao, String country, double lat, double lon) {
        GeoPoint c = new GeoPoint(lat, lon);
        GeoPoint e1 = c.interpolate(new GeoPoint(lat, lon + 1), 0.0), e2 = new GeoPoint(lat, lon + 0.035);
        var rw = List.of(new Airport.Runway("09", e1, e2, 45), new Airport.Runway("27", e2, e1, 45));
        return new Airport(icao, null, icao, country, c, 10, rw, new GeoPoint(lat + 0.003, lon + 0.01), false);
    }

    public static void testBookingPricingSeatsAndWallet() throws Exception {
        var a = airports();
        Path d = tmpDir();
        Ledger l = Ledger.open(d.resolve("l.jsonl"));
        l.credit("zach", 700, "REWARD_DAILY", "daily#x", "", 0);
        Booking b = new Booking(l, Booking.PricingConfig.defaults());
        var f = new Booking.Flight("EL101-0", "EL101", a.get("KSDC"), a.get("KROC"), AircraftType.HOPPER, 0, 3000);
        b.schedule(f);
        check(b.fareFor(f) == Booking.Fare.REGIONAL, "regional");
        long price = b.priceFor(f);
        check(price >= 100 && price <= 110, "short regional ~100 LC, was " + price);
        check(b.search("KSDC", "roch").size() == 1, "destination search by name");
        check(b.search("KSDC", "ROC").size() == 1, "search by IATA");
        check(b.availableSeats(f).size() == 48, "48 seats");
        var bp = b.purchase("zach", f.id(), "3C", 10);
        check(l.balance("zach") == 700 - price, "wallet deducted");
        check(b.purchase("zach", f.id(), "3C", 11).code().equals(bp.code()), "idempotent purchase");
        check(l.balance("zach") == 700 - price, "not charged twice");
        throwsEx(IllegalStateException.class, () -> b.purchase("alex", f.id(), "3C", 12), "seat taken");
        throwsEx(Ledger.InsufficientFunds.class, () -> b.purchase("alex", f.id(), "4A", 12), "alex has no money");
        check(b.availableSeats(f).size() == 47, "one seat sold");
        check(b.verify(f.id(), "zach").isPresent() && b.verify(f.id(), "alex").isEmpty(), "gate verification");

        // Restart: seat map and pass restored from the ledger
        Booking b2 = new Booking(Ledger.open(d.resolve("l.jsonl")), Booking.PricingConfig.defaults());
        b2.schedule(f);
        check(b2.restoreFromLedger() == 1, "restored one booking");
        check(b2.verify(f.id(), "zach").get().seat().equals("3C") && b2.availableSeats(f).size() == 47, "seat persisted");

        var intl = new Booking.Flight("X", "EL900", a.get("KROC"), fake("EGLL", "GB", 51.47, -0.45), AircraftType.HOPPER, 0, 0);
        check(b.fareFor(intl) == Booking.Fare.INTERNATIONAL && b.priceFor(intl) >= 1000, "international fare");
    }
}
