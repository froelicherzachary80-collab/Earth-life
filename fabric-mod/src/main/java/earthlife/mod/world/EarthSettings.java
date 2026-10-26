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
public record EarthSettings(String country, String state, String town, double lat, double lon,
                            String quality, boolean buildings, boolean roads, boolean realTerrain,
                            boolean transport, boolean simulation, String startTime, String scenario, String dayLength) {

    public enum Quality { LOW, MEDIUM, HIGH, ULTRA }
    public enum StartTime { REAL_TIME, MORNING, NOON, EVENING, NIGHT }
    public enum Scenario { NORMAL_SURVIVAL, NEW_LIFE, TRAVELER, CAREER_STARTER }
    public enum DayLength { VANILLA, REAL_TIME, TWO_HOURS, ONE_HOUR }

    /** A start location. {@code bundledData} = OpenStreetMap roads/buildings for it ship inside the mod jar. */
    public record Place(String country, String state, String town, double lat, double lon, boolean bundledData) {
        public String label() { return town + ", " + state + (bundledData ? "" : " (estimated terrain)"); }
    }

    /** Western New York (bundled starter region) first; the rest generate estimated terrain around real coordinates. */
    public static final List<Place> PLACES = List.of(
            new Place("United States", "New York", "Williamson", 43.2237, -77.1861, true),
            new Place("United States", "New York", "Pultneyville", 43.2795, -77.1858, true),
            new Place("United States", "New York", "Sodus", 43.2378, -77.0616, true),
            new Place("United States", "New York", "Marion", 43.1434, -77.1891, true),
            new Place("United States", "New York", "Ontario", 43.2209, -77.2830, true),
            new Place("United States", "New York", "Webster", 43.2123, -77.4300, true),
            new Place("United States", "New York", "Rochester", 43.1566, -77.6088, true),
            new Place("United States", "New York", "Wolcott", 43.2206, -76.8147, true),
            new Place("United States", "New York", "Canandaigua", 42.8875, -77.2817, true),
            new Place("United States", "New York", "Geneva", 42.8689, -76.9777, true),
            new Place("United States", "New York", "New York City", 40.7128, -74.0060, false),
            new Place("United States", "California", "San Francisco", 37.7749, -122.4194, false),
            new Place("United Kingdom", "England", "London", 51.5074, -0.1278, false),
            new Place("Japan", "Tokyo", "Tokyo", 35.6762, 139.6503, false),
            new Place("Australia", "New South Wales", "Sydney", -33.8688, 151.2093, false));

    public static final EarthSettings DEFAULT = new EarthSettings("United States", "New York", "Williamson", 43.2237, -77.1861,
            "HIGH", true, true, true, true, true, "REAL_TIME", "NEW_LIFE", "VANILLA");

    public static final Codec<EarthSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("country", DEFAULT.country).forGetter(EarthSettings::country),
            Codec.STRING.optionalFieldOf("state", DEFAULT.state).forGetter(EarthSettings::state),
            Codec.STRING.optionalFieldOf("town", DEFAULT.town).forGetter(EarthSettings::town),
            Codec.DOUBLE.optionalFieldOf("lat", DEFAULT.lat).forGetter(EarthSettings::lat),
            Codec.DOUBLE.optionalFieldOf("lon", DEFAULT.lon).forGetter(EarthSettings::lon),
            Codec.STRING.optionalFieldOf("quality", DEFAULT.quality).forGetter(EarthSettings::quality),
            Codec.BOOL.optionalFieldOf("buildings", true).forGetter(EarthSettings::buildings),
            Codec.BOOL.optionalFieldOf("roads", true).forGetter(EarthSettings::roads),
            Codec.BOOL.optionalFieldOf("real_terrain", true).forGetter(EarthSettings::realTerrain),
            Codec.BOOL.optionalFieldOf("transport", true).forGetter(EarthSettings::transport),
            Codec.BOOL.optionalFieldOf("simulation", true).forGetter(EarthSettings::simulation),
            Codec.STRING.optionalFieldOf("start_time", DEFAULT.startTime).forGetter(EarthSettings::startTime),
            Codec.STRING.optionalFieldOf("scenario", DEFAULT.scenario).forGetter(EarthSettings::scenario),
            Codec.STRING.optionalFieldOf("day_length", DEFAULT.dayLength).forGetter(EarthSettings::dayLength)
    ).apply(i, EarthSettings::new));

    public Quality qualityEnum() { return parse(Quality.class, quality, Quality.HIGH); }
    public StartTime startTimeEnum() { return parse(StartTime.class, startTime, StartTime.REAL_TIME); }
    public Scenario scenarioEnum() { return parse(Scenario.class, scenario, Scenario.NEW_LIFE); }
    public DayLength dayLengthEnum() { return parse(DayLength.class, dayLength, DayLength.VANILLA); }

    /** True when the start town lies in the bundled Western NY region (real OSM data available). */
    public boolean inWesternNy() { return lat > 42.6 && lat < 43.5 && lon > -78.3 && lon < -76.6; }

    public EarthSettings withPlace(Place p) {
        return new EarthSettings(p.country(), p.state(), p.town(), p.lat(), p.lon(), quality, buildings, roads, realTerrain, transport, simulation, startTime, scenario, dayLength);
    }
    public EarthSettings withQuality(Quality q) { return new EarthSettings(country, state, town, lat, lon, q.name(), buildings, roads, realTerrain, transport, simulation, startTime, scenario, dayLength); }
    public EarthSettings withToggles(boolean b, boolean r, boolean t, boolean tr, boolean s) { return new EarthSettings(country, state, town, lat, lon, quality, b, r, t, tr, s, startTime, scenario, dayLength); }
    public EarthSettings withStartTime(StartTime s) { return new EarthSettings(country, state, town, lat, lon, quality, buildings, roads, realTerrain, transport, simulation, s.name(), scenario, dayLength); }
    public EarthSettings withScenario(Scenario s) { return new EarthSettings(country, state, town, lat, lon, quality, buildings, roads, realTerrain, transport, simulation, startTime, s.name(), dayLength); }
    public EarthSettings withDayLength(DayLength d) { return new EarthSettings(country, state, town, lat, lon, quality, buildings, roads, realTerrain, transport, simulation, startTime, scenario, d.name()); }

    static <E extends Enum<E>> E parse(Class<E> c, String s, E def) {
        try { return Enum.valueOf(c, s.toUpperCase(Locale.ROOT)); } catch (Exception e) { return def; }
    }

    public static String pretty(Enum<?> e) {
        String s = e.name().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
