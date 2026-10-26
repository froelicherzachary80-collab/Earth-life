package earthlife.mod.client.screen;

import earthlife.mod.EarthLifeMod;
import earthlife.mod.world.EarthSettings;
import earthlife.mod.world.EarthSettings.Place;
import earthlife.mod.world.VoxelEarthChunkGenerator;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.PresetEditor;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

import java.util.*;

/**
 * "Customize" screen of the Earth world type (Create New World > More > World Type: Earth - 1:1 Real World).
 * The choices are written into this world's overworld generator settings, i.e. saved in its level.dat only.
 */
public final class EarthSettingsScreen extends Screen {
    public static final ResourceKey<WorldPreset> EARTH_PRESET = ResourceKey.create(Registries.WORLD_PRESET, EarthLifeMod.id("earthlife"));
    public static final PresetEditor EDITOR = (parent, ctx) -> new EarthSettingsScreen(parent, ctx);

    private final CreateWorldScreen parent;
    private EarthSettings settings;
    private final List<String> countries;

    public EarthSettingsScreen(CreateWorldScreen parent, WorldCreationContext ctx) {
        super(Component.translatable("earthlife.settings.title"));
        this.parent = parent;
        this.settings = ctx.selectedDimensions().overworld() instanceof VoxelEarthChunkGenerator g ? g.settings() : EarthSettings.DEFAULT;
        this.countries = EarthSettings.PLACES.stream().map(Place::country).distinct().toList();
    }

    public EarthSettings settings() { return settings; }

    List<String> states() { return EarthSettings.PLACES.stream().filter(p -> p.country().equals(settings.country())).map(Place::state).distinct().toList(); }
    List<Place> towns() { return EarthSettings.PLACES.stream().filter(p -> p.country().equals(settings.country()) && p.state().equals(settings.state())).toList(); }

    static <T> T next(List<T> l, T cur) { int i = l.indexOf(cur); return l.get((i + 1) % l.size()); }

    @Override protected void init() {
        int col = Math.min(170, (width - 30) / 2), lx = width / 2 - col - 5, rx = width / 2 + 5, y = 34, h = 20, gap = 23;
        // Start location
        addRenderableWidget(btn(lx, y, col, "Country: " + settings.country(), b -> {
            String c = next(countries, settings.country());
            Place first = EarthSettings.PLACES.stream().filter(p -> p.country().equals(c)).findFirst().orElseThrow();
            settings = settings.withPlace(first); rebuildWidgets(); }));
        addRenderableWidget(btn(rx, y, col, "State / region: " + settings.state(), b -> {
            String st = next(states(), settings.state());
            Place first = EarthSettings.PLACES.stream().filter(p -> p.country().equals(settings.country()) && p.state().equals(st)).findFirst().orElseThrow();
            settings = settings.withPlace(first); rebuildWidgets(); }));
        y += gap;
        Place cur = towns().stream().filter(p -> p.town().equals(settings.town())).findFirst().orElse(towns().get(0));
        Button town = btn(lx, y, col * 2 + 10, "Starting town: " + cur.label(), b -> { settings = settings.withPlace(next(towns(), cur)); rebuildWidgets(); });
        town.setTooltip(Tooltip.create(Component.literal(cur.bundledData()
                ? "Real roads, buildings and terrain from the bundled Western New York starter data (OpenStreetMap + USGS/NOAA terrain)."
                : "No map data is bundled for this town yet: terrain and buildings are estimated around its real coordinates.")));
        addRenderableWidget(town);
        y += gap + 6;
        addRenderableWidget(btn(lx, y, col, "Quality: " + EarthSettings.pretty(settings.qualityEnum()), b -> {
            settings = settings.withQuality(next(List.of(EarthSettings.Quality.values()), settings.qualityEnum())); rebuildWidgets(); }));
        addRenderableWidget(btn(rx, y, col, "Start time: " + EarthSettings.pretty(settings.startTimeEnum()), b -> {
            settings = settings.withStartTime(next(List.of(EarthSettings.StartTime.values()), settings.startTimeEnum())); rebuildWidgets(); }));
        y += gap;
        addRenderableWidget(btn(lx, y, col, "Scenario: " + EarthSettings.pretty(settings.scenarioEnum()), b -> {
            settings = settings.withScenario(next(List.of(EarthSettings.Scenario.values()), settings.scenarioEnum())); rebuildWidgets(); }))
                .setTooltip(Tooltip.create(Component.literal(scenarioHelp(settings.scenarioEnum()))));
        addRenderableWidget(btn(rx, y, col, "Day length: " + dayLabel(settings.dayLengthEnum()), b -> {
            settings = settings.withDayLength(next(List.of(EarthSettings.DayLength.values()), settings.dayLengthEnum())); rebuildWidgets(); }));
        y += gap + 6;
        boolean[] t = {settings.buildings(), settings.roads(), settings.realTerrain(), settings.transport(), settings.simulation()};
        String[] names = {"Buildings", "Roads", "Real terrain", "Transport & airports", "Residents & economy"};
        for (int i = 0; i < 5; i++) {
            final int k = i;
            int bx = i % 2 == 0 ? lx : rx;
            addRenderableWidget(btn(bx, y, col, names[i] + ": " + (t[i] ? "ON" : "OFF"), b -> {
                t[k] = !t[k];
                settings = settings.withToggles(t[0], t[1], t[2], t[3], t[4]);
                rebuildWidgets(); }));
            if (i % 2 == 1) y += gap;
        }
        y += gap + 4;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> done()).bounds(width / 2 - 155, height - 28, 150, h).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(width / 2 + 5, height - 28, 150, h).build());
    }

    static String dayLabel(EarthSettings.DayLength d) {
        return switch (d) { case VANILLA -> "20 min (vanilla)"; case REAL_TIME -> "Real time (24 h)"; case TWO_HOURS -> "2 hours"; case ONE_HOUR -> "1 hour"; };
    }

    static String scenarioHelp(EarthSettings.Scenario s) {
        return switch (s) {
            case NORMAL_SURVIVAL -> "Plain survival on the real Earth. No guided objectives or starting money.";
            case NEW_LIFE -> "You just moved to town with 250 LifeCoins. Guided objectives teach jobs, the map, residents and events.";
            case TRAVELER -> "Start at the nearest airport with 1,500 LifeCoins and a reason to fly.";
            case CAREER_STARTER -> "A store-clerk job is waiting on day one; objectives walk you through your first shift.";
        };
    }

    private Button btn(int x, int y, int w, String label, Button.OnPress press) {
        return Button.builder(Component.literal(label), press).bounds(x, y, w, 20).build();
    }

    private void done() {
        EarthSettings chosen = settings;
        parent.getUiState().updateDimensions((access, dims) -> dims.overworld() instanceof VoxelEarthChunkGenerator g
                ? dims.replaceOverworldGenerator(access, g.withSettings(chosen)) : dims);
        minecraft.gui.setScreen(parent);
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float d) {
        super.extractRenderState(g, mx, my, d);
        g.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
        g.centeredText(font, Component.literal(String.format("%.4f, %.4f · 1 block = 1 metre · %s", settings.lat(), settings.lon(),
                settings.inWesternNy() ? "Western New York starter region" : "estimated terrain region")), width / 2, 22, 0xFFA0C8FF);
        g.centeredText(font, Component.literal("Map data © OpenStreetMap contributors (ODbL). LifeCoins are fictional and have no real-money value."),
                width / 2, height - 42, 0xFF9E9E9E);
    }
}
