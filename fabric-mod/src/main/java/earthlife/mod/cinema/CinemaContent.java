package earthlife.mod.cinema;

import earthlife.mod.EarthLifeMod;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumables;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.*;
import java.util.function.Function;

/**
 * Cinema blocks, items and entities. All models and artwork are original (see tools/assets/cinema_assets.py):
 * recliners and 4D motion seats you can sit in, a popcorn machine, soda fountain and ticket kiosk, concession food
 * and drinks you can eat, red/cyan 3D glasses you wear on your head, and movie tickets.
 */
public final class CinemaContent {
    private CinemaContent() {}

    public static final List<Item> TAB_ITEMS = new ArrayList<>();
    /** Concession menu: item id -> price in LifeCoins (in-game currency only). */
    public static final Map<String, Integer> PRICES = new LinkedHashMap<>();

    public static final SeatBlock RECLINER = block("recliner", p -> new SeatBlock(p, false), BlockBehaviour.Properties.of().strength(1.2f).sound(SoundType.WOOL).noOcclusion());
    public static final SeatBlock MOTION_SEAT = block("motion_seat", p -> new SeatBlock(p, true), BlockBehaviour.Properties.of().strength(2f).sound(SoundType.METAL).noOcclusion());
    public static final CounterBlock POPCORN_MACHINE = block("popcorn_machine", p -> new CounterBlock(p, "concessions"), BlockBehaviour.Properties.of().strength(1.5f).sound(SoundType.METAL).noOcclusion().lightLevel(s -> 9));
    public static final CounterBlock SODA_FOUNTAIN = block("soda_fountain", p -> new CounterBlock(p, "concessions"), BlockBehaviour.Properties.of().strength(1.5f).sound(SoundType.METAL).noOcclusion());
    public static final CounterBlock TICKET_KIOSK = block("ticket_kiosk", p -> new CounterBlock(p, "tickets"), BlockBehaviour.Properties.of().strength(1.5f).sound(SoundType.METAL).noOcclusion().lightLevel(s -> 6));
    // ZMF Cinema multiplex furniture and equipment
    public static final SeatBlock LUXURY_RECLINER = block("luxury_recliner", p -> new SeatBlock(p, false), BlockBehaviour.Properties.of().strength(1.4f).sound(SoundType.WOOL).noOcclusion());
    public static final SeatBlock LOUNGE_SOFA = block("lounge_sofa", p -> new SeatBlock(p, false), BlockBehaviour.Properties.of().strength(1.0f).sound(SoundType.WOOL).noOcclusion());
    public static final CounterBlock CANDY_DISPLAY = block("candy_display", p -> new CounterBlock(p, "concessions"), BlockBehaviour.Properties.of().strength(1.2f).sound(SoundType.GLASS).noOcclusion().lightLevel(s -> 5));
    public static final CounterBlock SNACK_SHELF = block("snack_shelf", p -> new CounterBlock(p, "concessions"), BlockBehaviour.Properties.of().strength(1.2f).sound(SoundType.WOOD).noOcclusion());
    public static final DecorBlock PROJECTOR = block("projector", p -> new DecorBlock(p, Block.box(2, 0, 1, 14, 12, 15)), BlockBehaviour.Properties.of().strength(2f).sound(SoundType.METAL).noOcclusion().lightLevel(s -> 9));
    public static final DecorBlock CINEMA_SPEAKER = block("cinema_speaker", p -> new DecorBlock(p, Block.box(3, 0, 3, 13, 16, 13)), BlockBehaviour.Properties.of().strength(1.5f).sound(SoundType.WOOD).noOcclusion());
    public static final DecorBlock EFFECT_MODULE = block("effect_module", p -> new DecorBlock(p, Block.box(1, 1, 1, 15, 15, 15)), BlockBehaviour.Properties.of().strength(1.5f).sound(SoundType.METAL).noOcclusion().lightLevel(s -> 3));

    public static final Item POPCORN = food("popcorn", 5, 0.4f, false, 8);
    public static final Item LARGE_POPCORN = food("large_popcorn", 8, 0.45f, false, 11);
    public static final Item NACHOS = food("nachos", 6, 0.5f, false, 9);
    public static final Item HOT_DOG = food("hot_dog", 7, 0.7f, false, 7);
    public static final Item SOFT_PRETZEL = food("soft_pretzel", 5, 0.5f, false, 6);
    public static final Item COLA = food("cola", 2, 0.2f, true, 6);
    public static final Item LEMON_LIME_SODA = food("lemon_lime_soda", 2, 0.2f, true, 6);
    public static final Item ORANGE_SODA = food("orange_soda", 2, 0.2f, true, 6);
    public static final Item BLUE_SLUSHIE = food("blue_slushie", 3, 0.2f, true, 7);
    public static final Item BOTTLED_WATER = food("bottled_water", 1, 0.1f, true, 4);
    public static final Item CHOCOLATE_BAR = food("chocolate_bar", 3, 0.3f, false, 5);
    public static final Item GUMMY_BEARS = food("gummy_bears", 2, 0.2f, false, 5);
    public static final Item SOUR_WORMS = food("sour_worms", 2, 0.2f, false, 5);
    public static final Item LICORICE = food("licorice", 2, 0.2f, false, 5);
    public static final Item CANDY_MIX = food("candy_mix", 3, 0.3f, false, 6);
    public static final Item GLASSES_3D = item("glasses_3d", new Item.Properties().stacksTo(1).component(DataComponents.EQUIPPABLE,
            Equippable.builder(EquipmentSlot.HEAD).setEquipSound(SoundEvents.ARMOR_EQUIP_GENERIC).setCameraOverlay(EarthLifeMod.id("misc/glasses_3d_overlay")).build()), 0);
    public static final Item MOVIE_TICKET = item("movie_ticket", new Item.Properties().stacksTo(16), 0);
    public static final Item CINEMA_LOCATOR = itemOf("cinema_locator", earthlife.mod.zmf.LocatorItem::new, new Item.Properties().stacksTo(1));

    public static final ResourceKey<EntityType<?>> SEAT_KEY = ResourceKey.create(Registries.ENTITY_TYPE, EarthLifeMod.id("seat"));
    public static final EntityType<SeatEntity> SEAT = Registry.register(BuiltInRegistries.ENTITY_TYPE, SEAT_KEY,
            EntityType.Builder.<SeatEntity>of(SeatEntity::new, MobCategory.MISC).sized(0.4f, 0.4f).noSummon().clientTrackingRange(10).build(SEAT_KEY));
    public static final ResourceKey<EntityType<?>> SCREEN_KEY = ResourceKey.create(Registries.ENTITY_TYPE, EarthLifeMod.id("cinema_screen"));
    public static final EntityType<CinemaScreenEntity> SCREEN = Registry.register(BuiltInRegistries.ENTITY_TYPE, SCREEN_KEY,
            EntityType.Builder.<CinemaScreenEntity>of(CinemaScreenEntity::new, MobCategory.MISC).sized(1f, 1f).noSummon().clientTrackingRange(12).updateInterval(20).build(SCREEN_KEY));

    public static final CreativeModeTab TAB = Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, EarthLifeMod.id("cinema"),
            FabricCreativeModeTab.builder().title(Component.translatable("itemGroup.earthlife.cinema")).icon(() -> new ItemStack(POPCORN))
                    .displayItems((ctx, out) -> TAB_ITEMS.forEach(out::accept)).build());

    /** Forces class loading (registration happens in the static initialisers above). */
    public static void init() { EarthLifeMod.LOG.info("EarthLife cinema: {} items, {} concession products", TAB_ITEMS.size(), PRICES.size()); }

    public static Optional<Item> byId(String id) {
        return TAB_ITEMS.stream().filter(i -> BuiltInRegistries.ITEM.getKey(i).getPath().equals(id)).findFirst();
    }

    private static <T extends Block> T block(String name, Function<BlockBehaviour.Properties, T> f, BlockBehaviour.Properties p) {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, EarthLifeMod.id(name));
        T b = Registry.register(BuiltInRegistries.BLOCK, key, f.apply(p.setId(key)));
        ResourceKey<Item> ik = ResourceKey.create(Registries.ITEM, EarthLifeMod.id(name));
        TAB_ITEMS.add(Registry.register(BuiltInRegistries.ITEM, ik, new BlockItem(b, new Item.Properties().setId(ik).useBlockDescriptionPrefix())));
        return b;
    }

    private static Item item(String name, Item.Properties p, int price) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, EarthLifeMod.id(name));
        Item i = Registry.register(BuiltInRegistries.ITEM, key, new Item(p.setId(key)));
        TAB_ITEMS.add(i);
        if (price > 0) PRICES.put(name, price);
        return i;
    }

    private static Item itemOf(String name, Function<Item.Properties, Item> f, Item.Properties p) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, EarthLifeMod.id(name));
        Item i = Registry.register(BuiltInRegistries.ITEM, key, f.apply(p.setId(key)));
        TAB_ITEMS.add(i);
        return i;
    }

    private static Item food(String name, int nutrition, float saturation, boolean drink, int price) {
        FoodProperties fp = new FoodProperties.Builder().nutrition(nutrition).saturationModifier(saturation).alwaysEdible().build();
        Item.Properties p = new Item.Properties().stacksTo(16);
        p = drink ? p.food(fp, Consumables.defaultDrink().build()) : p.food(fp);
        return item(name, p, price);
    }
}
