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
 * "Customize" screen of the Earth world type (Create New World > World > World Type: Earth - 1:1 Real World).
 * Three pages (Start, World, Life) so it fits small windows. The choices are written into this world's overworld
 * generator settings, i.e. saved in its level.dat only.
 */
public final class EarthSettingsScreen extends Screen {
    public static final ResourceKey<WorldPreset> EARTH_PRESET = ResourceKey.create(Registries.WORLD_PRESET, EarthLifeMod.id("earthlife"));
    public static final PresetEditor EDITOR = (parent, ctx) -> new EarthSettingsScreen(parent, ctx);
    static final String[] PAGES = {"Start", "World", "Life"};

    private final CreateWorldScreen parent;
    private EarthSettings settings;
    private final List<String> countries;
    private int page;

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
        int col = Math.min(170, (width - 30) / 2), lx = width / 2 - col - 5, rx = width / 2 + 5, gap = height < 260 ? 22 : 24;
        int tabW = 80;
        for (int i = 0; i < PAGES.length; i++) {
            final int p = i;
            Button b = Button.builder(Component.literal(PAGES[i]), x -> { page = p; rebuildWidgets(); }).bounds(width / 2 - tabW * 3 / 2 + i * tabW, 32, tabW - 2, 18).build();
            b.active = i != page;
            addRenderableWidget(b);
        }
        int y = 56;
        if (page == 0) {
            addRenderableWidget(btn(lx, y, col, "Country: " + settings.country(), b -> {
                String c = next(countries, settings.country());
                settings = settings.withPlace(EarthSettings.PLACES.stream().filter(p -> p.country().equals(c)).findFirst().orElseThrow()); rebuildWidgets(); }));
            addRenderableWidget(btn(rx, y, col, "State / region: " + settings.state(), b -> {
                String st = next(states(), settings.state());
                settings = settings.withPlace(EarthSettings.PLACES.stream().filter(p -> p.country().equals(settings.country()) && p.state().equals(st)).findFirst().orElseThrow());
                rebuildWidgets(); }));
            y += gap;
            Place cur = towns().stream().filter(p -> p.town().equals(settings.town())).findFirst().orElse(towns().get(0));
            Button town = btn(lx, y, col * 2 + 10, "Starting town: " + cur.label(), b -> { settings = settings.withPlace(next(towns(), cur)); rebuildWidgets(); });
            town.setTooltip(Tooltip.create(Component.literal(cur.bundledData()
                    ? "Real roads, buildings and terrain from the bundled Western New York starter data (OpenStreetMap + USGS/NOAA terrain)."
                    : settings.inWesternNy() ? "Real terrain from the Western NY region grid; this town is outside the bundled road/building data, so buildings are estimated."
                    : "No map data is bundled for this place yet: terrain and buildings are estimated around its real coordinates.")));
            addRenderableWidget(town);
            y += gap;
            addRenderableWidget(btn(lx, y, col, "Quality: " + EarthSettings.pretty(settings.qualityEnum()), b -> {
                settings = settings.withQuality(next(List.of(EarthSettings.Quality.values()), settings.qualityEnum())); rebuildWidgets(); }))
                    .setTooltip(Tooltip.create(Component.literal(qualityHelp(settings.qualityEnum()))));
            addRenderableWidget(btn(rx, y, col, "Start time: " + startLabel(settings.startTimeEnum()), b -> {
                settings = settings.withStartTime(next(List.of(EarthSettings.StartTime.values()), settings.startTimeEnum())); rebuildWidgets(); }));
            y += gap;
            addRenderableWidget(btn(lx, y, col, "Scenario: " + EarthSettings.pretty(settings.scenarioEnum()), b -> {
                settings = settings.withScenario(next(List.of(EarthSettings.Scenario.values()), settings.scenarioEnum())); rebuildWidgets(); }))
                    .setTooltip(Tooltip.create(Component.literal(scenarioHelp(settings.scenarioEnum()))));
            addRenderableWidget(btn(rx, y, col, "Day length: " + dayLabel(settings.dayLengthEnum()), b -> {
                settings = settings.withDayLength(next(List.of(EarthSettings.DayLength.values()), settings.dayLengthEnum())); rebuildWidgets(); }));
        } else {
            int[] idx = page == 1 ? new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9} : new int[]{10, 11, 12};
            boolean[] t = settings.options().array();
            for (int n = 0; n < idx.length; n++) {
                final int k = idx[n];
                int bx = n % 2 == 0 ? lx : rx;
                Button b = btn(bx, y, col, EarthSettings.Options.NAMES[k] + ": " + (t[k] ? "ON" : "OFF"), x -> {
                    t[k] = !t[k];
                    settings = settings.withOptions(EarthSettings.Options.of(t));
                    rebuildWidgets(); });
                b.setTooltip(Tooltip.create(Component.literal(optionHelp(k))));
                addRenderableWidget(b);
                if (n % 2 == 1) y += gap;
            }
            if (page == 2) {
                if (idx.length % 2 == 1) y += gap;
                addRenderableWidget(btn(lx, y, col, "Resident density: " + EarthSettings.pretty(settings.npcDensityEnum()), x -> {
                    settings = settings.withNpcDensity(next(List.of(EarthSettings.NpcDensity.values()), settings.npcDensityEnum())); rebuildWidgets(); }))
                        .setTooltip(Tooltip.create(Component.literal("How many residents walk around near each player: Low 15, Normal 40, High 80.")));
            }
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> done()).bounds(width / 2 - 155, height - 26, 150, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(width / 2 + 5, height - 26, 150, 20).build());
    }

    static String optionHelp(int k) {
        return switch (k) {
            case 0 -> "Buildings from OpenStreetMap footprints, heights, roof shapes and colours.";
            case 1 -> "Fill unmapped streets with estimated houses and shops (marked as estimated).";
            case 2 -> "Furnish houses (kitchen corner, bed, storage) and stores (shelves, counter). Needs Detailed or Ultra quality. Generated floor plans, not real interiors.";
            case 3 -> "Real roads with lane markings. Off: grass where roads would be (runways stay).";
            case 4 -> "Street lamps along town roads.";
            case 5 -> "Railway lines from OpenStreetMap (gravel beds).";
            case 6 -> "Real elevation (USGS/AWS terrain). Off: flat land at the start town's height.";
            case 7 -> "Trees from mapped forests, orchards and parks.";
            case 8 -> "Crop fields where land use is farmland.";
            case 9 -> "Mapped ponds, lakes and rivers. Lake Ontario always stays.";
            case 10 -> "Generated airport terminals next to real runways, and scheduled flights.";
            case 11 -> "Residents with homes, jobs and routines; jobs, wages and the LifeCoin economy.";
            default -> "Real holidays, sports and concerts at their real times (Halloween week now).";
        };
    }

    static String qualityHelp(EarthSettings.Quality q) {
        return switch (q) {
            case PERFORMANCE -> "Plain walls (no window rows or chimneys), half the trees, no street lamps or interiors. Fastest.";
            case BALANCED -> "Windows on every floor, chimneys, street lamps. No interiors.";
            case DETAILED -> "Balanced plus furnished interiors.";
            case ULTRA -> "Detailed plus denser street lighting.";
        };
    }

    static String startLabel(EarthSettings.StartTime t) {
        return switch (t) { case REAL_TIME -> "Real local time"; default -> EarthSettings.pretty(t); };
    }

    static String dayLabel(EarthSettings.DayLength d) {
        return switch (d) { case VANILLA -> "Minecraft (20 min)"; case REAL_TIME -> "Real time (24 h)"; case TWO_HOURS -> "Custom: 2 hours"; case ONE_HOUR -> "Custom: 1 hour"; };
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
        g.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
        g.centeredText(font, Component.literal(String.format("%s, %s, %s · %.4f, %.4f · 1 block = 1 metre", settings.town(), settings.state(), settings.country(),
                settings.lat(), settings.lon())), width / 2, 19, 0xFFA0C8FF);
        g.centeredText(font, Component.literal("Map data © OpenStreetMap contributors (ODbL) · LifeCoins are fictional, no real-money value"),
                width / 2, height - 38, 0xFF9E9E9E);
    }
}
