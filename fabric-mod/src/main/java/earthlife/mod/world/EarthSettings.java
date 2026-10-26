package earthlife.mod.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.Locale;

/**
 * Per-world Earth options chosen on the "Customize" screen of the Earth world type. They are stored inside the
 * overworld generator's settings, so Minecraft writes them into this world's level.dat and nothing leaks into
 * other worlds (Default/Superflat worlds never see them).
 */
public record EarthSettings(String country, String state, String town, double lat, double lon, String quality,
                            Options options, String startTime, String scenario, String dayLength, String npcDensity) {

    public enum Quality { PERFORMANCE, BALANCED, DETAILED, ULTRA }
    public enum StartTime { REAL_TIME, MORNING, NOON, EVENING, NIGHT }
    public enum Scenario { NORMAL_SURVIVAL, NEW_LIFE, TRAVELER, CAREER_STARTER }
    public enum DayLength { VANILLA, REAL_TIME, TWO_HOURS, ONE_HOUR }
    /** Residents shown as entities around each player: Low 15, Normal 40, High 80. */
    public enum NpcDensity { LOW, NORMAL, HIGH; public int cap() { return switch (this) { case LOW -> 15; case NORMAL -> 40; case HIGH -> 80; }; } }

    /** Every toggle here changes what is generated or simulated (no placeholders). */
    public record Options(boolean mappedBuildings, boolean estimatedBuildings, boolean interiors, boolean roads, boolean streetDetails,
                          boolean railways, boolean realTerrain, boolean forests, boolean farmland, boolean waterways,
                          boolean airports, boolean residents, boolean events) {
        public static final Options ALL = new Options(true, true, true, true, true, true, true, true, true, true, true, true, true);
        public static final Codec<Options> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.BOOL.optionalFieldOf("mapped_buildings", true).forGetter(Options::mappedBuildings),
                Codec.BOOL.optionalFieldOf("estimated_buildings", true).forGetter(Options::estimatedBuildings),
                Codec.BOOL.optionalFieldOf("interiors", true).forGetter(Options::interiors),
                Codec.BOOL.optionalFieldOf("roads", true).forGetter(Options::roads),
                Codec.BOOL.optionalFieldOf("street_details", true).forGetter(Options::streetDetails),
                Codec.BOOL.optionalFieldOf("railways", true).forGetter(Options::railways),
                Codec.BOOL.optionalFieldOf("real_terrain", true).forGetter(Options::realTerrain),
                Codec.BOOL.optionalFieldOf("forests", true).forGetter(Options::forests),
                Codec.BOOL.optionalFieldOf("farmland", true).forGetter(Options::farmland),
                Codec.BOOL.optionalFieldOf("waterways", true).forGetter(Options::waterways),
                Codec.BOOL.optionalFieldOf("airports", true).forGetter(Options::airports),
                Codec.BOOL.optionalFieldOf("residents", true).forGetter(Options::residents),
                Codec.BOOL.optionalFieldOf("events", true).forGetter(Options::events)
        ).apply(i, Options::new));

        public boolean[] array() { return new boolean[]{mappedBuildings, estimatedBuildings, interiors, roads, streetDetails, railways, realTerrain, forests, farmland, waterways, airports, residents, events}; }
        public static Options of(boolean[] a) { return new Options(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8], a[9], a[10], a[11], a[12]); }
        public static final String[] NAMES = {"Mapped buildings", "Estimated buildings", "Interiors", "Roads", "Street lamps", "Railways",
                "Real elevation", "Forests & trees", "Farmland", "Lakes & rivers", "Airports & flights", "Residents, jobs & economy", "Live events"};
    }

    /** A start location. {@code bundledData} = OpenStreetMap roads/buildings for it ship inside the mod jar. */
    public record Place(String country, String state, String town, double lat, double lon, boolean bundledData) {
        public String label() {
            boolean wny = lat > 42.6 && lat < 43.5 && lon > -78.3 && lon < -76.6;
            return town + ", " + state + (bundledData ? "" : wny ? " (real terrain, estimated buildings)" : " (estimated terrain)");
        }
    }

    /** Western NY towns inside the bundled starter data first; the rest generate estimated buildings around real coordinates. */
    public static final List<Place> PLACES = List.of(
            new Place("United States", "New York", "Williamson", 43.2237, -77.1861, true),
            new Place("United States", "New York", "Pultneyville", 43.2795, -77.1858, true),
            new Place("United States", "New York", "Sodus", 43.2378, -77.0616, true),
            new Place("United States", "New York", "Sodus Point", 43.2706, -76.9927, true),
            new Place("United States", "New York", "Marion", 43.1434, -77.1891, true),
            new Place("United States", "New York", "Ontario", 43.2209, -77.2830, true),
            new Place("United States", "New York", "Walworth", 43.1387, -77.2722, true),
            new Place("United States", "New York", "Webster", 43.2123, -77.4300, true),
            new Place("United States", "New York", "Rochester", 43.1566, -77.6088, false),
            new Place("United States", "New York", "Wolcott", 43.2206, -76.8147, false),
            new Place("United States", "New York", "Canandaigua", 42.8875, -77.2817, false),
            new Place("United States", "New York", "Geneva", 42.8689, -76.9777, false),
            new Place("United States", "New York", "New York City", 40.7128, -74.0060, false),
            new Place("United States", "California", "Los Angeles", 34.0522, -118.2437, false),
            new Place("United States", "California", "San Francisco", 37.7749, -122.4194, false),
            new Place("United Kingdom", "England", "London", 51.5074, -0.1278, false),
            new Place("Japan", "Tokyo", "Tokyo", 35.6762, 139.6503, false),
            new Place("Australia", "New South Wales", "Sydney", -33.8688, 151.2093, false));

    public static final EarthSettings DEFAULT = new EarthSettings("United States", "New York", "Williamson", 43.2237, -77.1861,
            "DETAILED", Options.ALL, "REAL_TIME", "NEW_LIFE", "VANILLA", "NORMAL");

    public static final Codec<EarthSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("country", DEFAULT.country).forGetter(EarthSettings::country),
            Codec.STRING.optionalFieldOf("state", DEFAULT.state).forGetter(EarthSettings::state),
            Codec.STRING.optionalFieldOf("town", DEFAULT.town).forGetter(EarthSettings::town),
            Codec.DOUBLE.optionalFieldOf("lat", DEFAULT.lat).forGetter(EarthSettings::lat),
            Codec.DOUBLE.optionalFieldOf("lon", DEFAULT.lon).forGetter(EarthSettings::lon),
            Codec.STRING.optionalFieldOf("quality", DEFAULT.quality).forGetter(EarthSettings::quality),
            Options.CODEC.optionalFieldOf("options", Options.ALL).forGetter(EarthSettings::options),
            Codec.STRING.optionalFieldOf("start_time", DEFAULT.startTime).forGetter(EarthSettings::startTime),
            Codec.STRING.optionalFieldOf("scenario", DEFAULT.scenario).forGetter(EarthSettings::scenario),
            Codec.STRING.optionalFieldOf("day_length", DEFAULT.dayLength).forGetter(EarthSettings::dayLength),
            Codec.STRING.optionalFieldOf("npc_density", DEFAULT.npcDensity).forGetter(EarthSettings::npcDensity)
    ).apply(i, EarthSettings::new));

    public Quality qualityEnum() {
        return switch (quality.toUpperCase(Locale.ROOT)) {     // 0.4 development builds used LOW/MEDIUM/HIGH
            case "LOW" -> Quality.PERFORMANCE; case "MEDIUM" -> Quality.BALANCED; case "HIGH" -> Quality.DETAILED;
            default -> parse(Quality.class, quality, Quality.DETAILED);
        };
    }
    public StartTime startTimeEnum() { return parse(StartTime.class, startTime, StartTime.REAL_TIME); }
    public Scenario scenarioEnum() { return parse(Scenario.class, scenario, Scenario.NEW_LIFE); }
    public DayLength dayLengthEnum() { return parse(DayLength.class, dayLength, DayLength.VANILLA); }
    public NpcDensity npcDensityEnum() { return parse(NpcDensity.class, npcDensity, NpcDensity.NORMAL); }

    // Shorthands used by the generator and services.
    public boolean buildings() { return options.mappedBuildings() || options.estimatedBuildings(); }
    public boolean roads() { return options.roads(); }
    public boolean realTerrain() { return options.realTerrain(); }
    public boolean transport() { return options.airports(); }
    public boolean simulation() { return options.residents(); }
    /** Interiors are furnished from Detailed quality up (and when the Interiors option is on). */
    public boolean furnish() { return options.interiors() && qualityEnum().ordinal() >= Quality.DETAILED.ordinal(); }

    /** True when the start town lies in the Western NY region (bundled terrain; roads/buildings inside the starter box). */
    public boolean inWesternNy() { return lat > 42.6 && lat < 43.5 && lon > -78.3 && lon < -76.6; }

    public EarthSettings withPlace(Place p) { return new EarthSettings(p.country(), p.state(), p.town(), p.lat(), p.lon(), quality, options, startTime, scenario, dayLength, npcDensity); }
    public EarthSettings withQuality(Quality q) { return new EarthSettings(country, state, town, lat, lon, q.name(), options, startTime, scenario, dayLength, npcDensity); }
    public EarthSettings withOptions(Options o) { return new EarthSettings(country, state, town, lat, lon, quality, o, startTime, scenario, dayLength, npcDensity); }
    public EarthSettings withStartTime(StartTime s) { return new EarthSettings(country, state, town, lat, lon, quality, options, s.name(), scenario, dayLength, npcDensity); }
    public EarthSettings withScenario(Scenario s) { return new EarthSettings(country, state, town, lat, lon, quality, options, startTime, s.name(), dayLength, npcDensity); }
    public EarthSettings withDayLength(DayLength d) { return new EarthSettings(country, state, town, lat, lon, quality, options, startTime, scenario, d.name(), npcDensity); }

    public EarthSettings withNpcDensity(NpcDensity d) { return new EarthSettings(country, state, town, lat, lon, quality, options, startTime, scenario, dayLength, d.name()); }

    static <E extends Enum<E>> E parse(Class<E> c, String s, E def) {
        try { return Enum.valueOf(c, s.toUpperCase(Locale.ROOT)); } catch (Exception e) { return def; }
    }

    public static String pretty(Enum<?> e) {
        String s = e.name().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
