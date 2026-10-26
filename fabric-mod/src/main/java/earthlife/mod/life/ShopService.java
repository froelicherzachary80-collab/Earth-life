package earthlife.mod.life;

import earthlife.core.world.Features;
import earthlife.mod.EarthLifeServices;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;

import java.util.*;

/**
 * Stores you can walk into: every mapped (or estimated) shop building sells goods for LifeCoins while you stand in or
 * next to it. What a store sells depends on what it is (grocery, pharmacy/general, restaurant, office supplies...),
 * guessed from its OpenStreetMap name/kind. Purchases are server-side ledger debits: no negative balances, no
 * duplicate charges (each purchase has a unique reference).
 */
public final class ShopService {
    public record Store(String name, String type, double x, double z) {}
    public record Ware(String id, String label, Item item, int count, long price, Integer dye) {}
    // (catalog items are resolved at class init, after registries are frozen)

    static final Map<String, List<Ware>> CATALOG = new LinkedHashMap<>();
    static {
        CATALOG.put("grocery", List.of(
                new Ware("bread", "Bread x3", Items.BREAD, 3, 6, null), new Ware("apples", "Apples x4", Items.APPLE, 4, 6, null),
                new Ware("beef", "Steak x2", Items.COOKED_BEEF, 2, 12, null), new Ware("potatoes", "Baked potatoes x4", Items.BAKED_POTATO, 4, 8, null),
                new Ware("carrots", "Carrots x6", Items.CARROT, 6, 5, null), new Ware("cake", "Cake", Items.CAKE, 1, 25, null),
                new Ware("seeds", "Wheat seeds x8", Items.WHEAT_SEEDS, 8, 4, null), new Ware("pumpkin", "Pumpkin (carve it!)", Items.PUMPKIN, 1, 8, null)));
        CATALOG.put("restaurant", List.of(
                new Ware("meal", "Hot meal (steak x2 + bread)", Items.COOKED_BEEF, 2, 14, null), new Ware("pie", "Pumpkin pie x2", Items.PUMPKIN_PIE, 2, 10, null),
                new Ware("cookies", "Cookies x6", Items.COOKIE, 6, 6, null), new Ware("stew", "Mushroom stew", Items.MUSHROOM_STEW, 1, 8, null)));
        CATALOG.put("general", List.of(
                new Ware("torches", "Torches x16", Items.TORCH, 16, 8, null), new Ware("lantern", "Lantern", Items.LANTERN, 1, 12, null),
                new Ware("shears", "Shears", Items.SHEARS, 1, 25, null), new Ware("rod", "Fishing rod", Items.FISHING_ROD, 1, 20, null),
                new Ware("bed", "Bed", earthlife.mod.McCompat.item("minecraft:red_bed"), 1, 40, null), new Ware("chest", "Chest", Items.CHEST, 1, 15, null),
                new Ware("table", "Crafting table", Items.CRAFTING_TABLE, 1, 10, null), new Ware("bread", "Bread x3", Items.BREAD, 3, 7, null),
                new Ware("shirt", "Shirt (blue)", Items.LEATHER_CHESTPLATE, 1, 30, 0x3B6EB5), new Ware("jeans", "Jeans", Items.LEATHER_LEGGINGS, 1, 30, 0x2B3A55),
                new Ware("shoes", "Shoes", Items.LEATHER_BOOTS, 1, 20, 0x3A2A1E), new Ware("cap", "Cap (red)", Items.LEATHER_HELMET, 1, 15, 0xB02E26)));
        CATALOG.put("hardware", List.of(
                new Ware("pickaxe", "Iron pickaxe", Items.IRON_PICKAXE, 1, 60, null), new Ware("axe", "Iron axe", Items.IRON_AXE, 1, 55, null),
                new Ware("shovel", "Iron shovel", Items.IRON_SHOVEL, 1, 40, null), new Ware("planks", "Oak planks x32", Items.OAK_PLANKS, 32, 20, null),
                new Ware("glass", "Glass x16", Items.GLASS, 16, 24, null), new Ware("bricks", "Bricks x32", Items.BRICKS, 32, 45, null),
                new Ware("bonemeal", "Bone meal x16", Items.BONE_MEAL, 16, 10, null), new Ware("torches", "Torches x16", Items.TORCH, 16, 8, null)));
        CATALOG.put("office", List.of(
                new Ware("paper", "Paper x16", Items.PAPER, 16, 8, null), new Ware("book", "Book & quill", Items.WRITABLE_BOOK, 1, 12, null),
                new Ware("map", "Empty map", Items.MAP, 1, 10, null), new Ware("compass", "Compass", Items.COMPASS, 1, 18, null)));
    }

    private final List<Store> stores = new ArrayList<>();

    public ShopService(Features f) {
        for (Features.Building b : f.buildings) {
            String k = b.kind() == null ? "" : b.kind();
            if (!(k.equals("retail") || k.equals("commercial") || k.equals("supermarket") || k.equals("kiosk") || k.equals("shop"))) continue;
            String name = b.tags() != null && b.tags().name() != null ? b.tags().name() : (b.prov() == Features.Provenance.ESTIMATED ? "Corner store" : "Local store");
            double[] bb = Features.bbox(b.footprint(), 0);
            stores.add(new Store(name, typeOf(name), (bb[0] + bb[2]) / 2, (bb[1] + bb[3]) / 2));
        }
    }

    static String typeOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.matches(".*(tops|aldi|wegmans|save-a-lot|market|grocery|food|farm).*")) return "grocery";
        if (n.matches(".*(pizza|grill|king|kfc|mcdonald|buffet|robin|diner|restaurant|cafe|subway|wendy|taco|dunkin|tim horton).*")) return "restaurant";
        if (n.matches(".*(hardware|lowe|home depot|tractor|ace|true value|lumber).*")) return "hardware";
        if (n.matches(".*(staples|office).*")) return "office";
        return "general";
    }

    public int count() { return stores.size(); }

    /** The store you're in or next to (within 25 m of its centre), else empty. */
    public Optional<Store> at(double x, double z) {
        return stores.stream().filter(s -> Math.hypot(s.x - x, s.z - z) < 25).min(Comparator.comparingDouble(s -> Math.hypot(s.x - x, s.z - z)));
    }

    public Optional<Store> nearest(double x, double z) {
        return stores.stream().min(Comparator.comparingDouble(s -> Math.hypot(s.x - x, s.z - z)));
    }

    public static List<Ware> wares(Store s) { return CATALOG.getOrDefault(s.type, CATALOG.get("general")); }

    public String buy(ServerPlayer p, String wareId) throws Exception {
        var es = EarthLifeServices.get();
        Store s = at(p.getX(), p.getZ()).orElse(null);
        if (s == null) return "You need to be in a store to buy things. " + nearest(p.getX(), p.getZ())
                .map(n -> String.format("Nearest: %s, %.0f m away.", n.name, Math.hypot(n.x - p.getX(), n.z - p.getZ()))).orElse("");
        Ware w = wares(s).stream().filter(x -> x.id.equals(wareId)).findFirst().orElse(null);
        if (w == null) return s.name + " doesn't sell that.";
        es.ledger.debit(p.getStringUUID(), w.price, "STORE", "store#" + p.getStringUUID() + "#" + System.nanoTime(), w.label + " at " + s.name, p.level().getGameTime());
        ItemStack stack = new ItemStack(w.item, w.count);
        if (w.dye != null) stack.set(DataComponents.DYED_COLOR, new DyedItemColor(w.dye));
        if (!p.addItem(stack)) p.level().addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(p.level(), p.getX(), p.getY() + 0.5, p.getZ(), stack));
        es.life.complete(p, "purchase");
        return "Bought " + w.label + " at " + s.name + " for " + w.price + " LC.";
    }
}
