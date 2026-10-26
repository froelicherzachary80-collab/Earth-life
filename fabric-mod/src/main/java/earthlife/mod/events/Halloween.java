package earthlife.mod.events;

import earthlife.core.npc.Npc;
import earthlife.core.time.GameClock;
import earthlife.core.world.Towns;
import earthlife.mod.EarthLifeServices;
import earthlife.mod.McCompat;
import earthlife.core.life.PlayerLife;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.util.*;

/**
 * Halloween, played for real during Halloween week (Oct 24 - Nov 1, real calendar in the world's time zone):
 *  - residents wear costumes (pumpkins, skulls, mob heads);
 *  - trick-or-treat: in the evening (game time 17:00-22:59), choose "Trick or treat!" when talking to a resident -
 *    each resident gives candy once per year;
 *  - costume contest at any town centre in the evening: your costume is scored against the town's entrants;
 *  - pumpkin-carving contest: carved pumpkins / jack o'lanterns placed around your home are counted.
 * Prizes are LifeCoins through the normal ledger (each contest once per player per year).
 */
public final class Halloween {
    private Halloween() {}

    /** Operators can force Halloween on outside the season for testing (/earthlife admin halloween on). */
    public static volatile boolean forced;

    public static LocalDate today(ZoneId zone) { return LocalDate.now(zone); }

    public static boolean isSeason(ZoneId zone) { return forced || earthlife.core.events.HalloweenCalendar.isSeason(today(zone)); }

    public static boolean isEvening(long dayTime) { return forced || earthlife.core.events.HalloweenCalendar.isEveningHour(GameClock.hour(dayTime)); }

    /** "Halloween in 6 days", "Halloween tonight!", or empty out of season. */
    public static String countdown(ZoneId zone) {
        String c = earthlife.core.events.HalloweenCalendar.countdown(today(zone));
        return c.isEmpty() && forced ? "🎃 Halloween (forced on by an operator)" : c;
    }

    static final Item[] COSTUMES = {Items.CARVED_PUMPKIN, Items.SKELETON_SKULL, Items.ZOMBIE_HEAD, Items.CREEPER_HEAD, Items.PIGLIN_HEAD, Blocks.JACK_O_LANTERN.asItem()};

    /** Gives about 60% of residents a costume head for the season (deterministic per resident). */
    public static void dress(LivingEntity resident, String residentId, boolean season) {
        ItemStack want = ItemStack.EMPTY;
        int h = Math.floorMod(residentId.hashCode() * 31 + 7, 100);
        if (season && h < 60) want = new ItemStack(COSTUMES[h % COSTUMES.length]);
        if (!ItemStack.matches(resident.getItemBySlot(EquipmentSlot.HEAD), want)) resident.setItemSlot(EquipmentSlot.HEAD, want);
    }

    static ItemStack candy(int n, Random r) {
        Item[] kinds = {Items.COOKIE, Items.PUMPKIN_PIE, Items.SWEET_BERRIES, Items.HONEY_BOTTLE};
        String[] names = {"Candy corn cookie", "Mini pumpkin pie", "Gummy berries", "Caramel apple syrup"};
        int k = r.nextInt(kinds.length);
        ItemStack s = new ItemStack(kinds[k], n);
        s.set(DataComponents.CUSTOM_NAME, Component.literal("§6" + names[k] + " (Halloween candy)"));
        return s;
    }

    /** Returns what the resident says. Candy goes straight into the inventory. */
    public static String trickOrTreat(ServerPlayer p, Npc npc) {
        var s = EarthLifeServices.get();
        ZoneId zone = s.time.clock().zone();
        if (!isSeason(zone)) return "Halloween? That's not until the end of October!";
        if (!isEvening(McCompat.dayTime(p.level()))) return "Trick-or-treating starts at dusk - come back this evening (17:00)!";
        PlayerLife life = s.life.life(p);
        String key = today(zone).getYear() + ":" + npc.id();
        if (!life.treated.add(key)) return "I already gave you candy this year - save some for the other kids!";
        Random r = new Random(Objects.hash(key, p.getStringUUID()));
        int n = 1 + r.nextInt(3);
        if (npc.personality() == Npc.Personality.GRUMPY && r.nextInt(3) == 0) {
            life.markDirty();
            return "Trick! ...Fine, fine. No candy here, but nice costume.";
        }
        if (npc.personality() == Npc.Personality.CHEERFUL || npc.personality() == Npc.Personality.FRIENDLY) n++;
        p.addItem(candy(n, r));
        life.candy += n;
        life.markDirty();
        s.life.complete(p, "event");
        return "Happy Halloween! Here's " + n + " piece" + (n == 1 ? "" : "s") + " of candy. (" + life.candy + " collected so far)";
    }

    /** Costume points: head 3-10, each dyed leather armour piece 2. */
    public static int costumeScore(ServerPlayer p) {
        int score = 0;
        ItemStack head = p.getItemBySlot(EquipmentSlot.HEAD);
        Item h = head.getItem();
        if (h == Items.CARVED_PUMPKIN) score += 3;
        else if (h == Blocks.JACK_O_LANTERN.asItem()) score += 4;
        else if (h == Items.SKELETON_SKULL || h == Items.ZOMBIE_HEAD || h == Items.CREEPER_HEAD || h == Items.PIGLIN_HEAD) score += 6;
        else if (h == Items.WITHER_SKELETON_SKULL) score += 8;
        else if (h == Items.DRAGON_HEAD) score += 10;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack a = p.getItemBySlot(slot);
            Item i = a.getItem();
            if (i == Items.LEATHER_CHESTPLATE || i == Items.LEATHER_LEGGINGS || i == Items.LEATHER_BOOTS) score += a.has(DataComponents.DYED_COLOR) ? 2 : 1;
        }
        return score;
    }

    public static String costumeContest(ServerPlayer p) throws Exception {
        var s = EarthLifeServices.get();
        ZoneId zone = s.time.clock().zone();
        if (!isSeason(zone)) return "The costume contest runs during Halloween week (Oct 24 - Nov 1).";
        if (!isEvening(McCompat.dayTime(p.level()))) return "The costume parade starts at 17:00 in-game. Come back this evening.";
        var here = s.projection.fromBlock(p.getX(), p.getZ());
        Towns.Town town = s.towns.isEmpty() ? null : Towns.nearest(s.towns, here);
        if (town == null || town.centre().distanceTo(here) > 150) return "Go to a town centre for the costume parade (nearest: " + (town == null ? "?" : town.name()) + ").";
        PlayerLife life = s.life.life(p);
        String key = today(zone).getYear() + ":costume";
        if (life.contests.contains(key)) return "You've already entered this year's costume contest.";
        int mine = costumeScore(p);
        if (mine == 0) return "You need a costume! Wear a carved pumpkin, a mob head or dyed leather armour.";
        Random r = new Random(Objects.hash(town.name(), today(zone).getYear()));
        List<Integer> others = new ArrayList<>();
        for (int i = 0; i < 6; i++) others.add(2 + r.nextInt(9));
        int place = 1 + (int) others.stream().filter(o -> o > mine).count();
        long prize = earthlife.core.events.HalloweenCalendar.costumePrize(place);
        life.contests.add(key); life.markDirty();
        s.ledger.credit(p.getStringUUID(), prize, "CONTEST", "contest#costume#" + key, "Halloween costume contest, " + town.name(), p.level().getGameTime());
        s.life.complete(p, "event");
        return String.format("🎃 Costume contest in %s: your costume scored %d (entrants: %s). You placed #%d of 7 and won %d LC!", town.name(), mine, others, place, prize);
    }

    public static String carvingContest(ServerPlayer p) throws Exception {
        var s = EarthLifeServices.get();
        ZoneId zone = s.time.clock().zone();
        if (!isSeason(zone)) return "The pumpkin-carving contest runs during Halloween week (Oct 24 - Nov 1).";
        PlayerLife life = s.life.life(p);
        if (life.home == null) return "Set your home first (/earthlife home set, or on the LifeMap) - judges count pumpkins around your home.";
        String key = today(zone).getYear() + ":carving";
        if (life.contests.contains(key)) return "You've already entered this year's carving contest.";
        ServerLevel level = (ServerLevel) p.level();
        int count = 0;
        BlockPos c = BlockPos.containing(life.home.x(), life.home.y(), life.home.z());
        for (BlockPos q : BlockPos.betweenClosed(c.offset(-12, -6, -12), c.offset(12, 6, 12))) {
            var st = level.getBlockState(q);
            if (st.is(Blocks.CARVED_PUMPKIN) || st.is(Blocks.JACK_O_LANTERN)) count++;
        }
        if (count == 0) return "No carved pumpkins or jack o'lanterns within 12 blocks of your home yet. Carve pumpkins with shears!";
        long prize = earthlife.core.events.HalloweenCalendar.carvingPrize(count);
        life.contests.add(key); life.markDirty();
        s.ledger.credit(p.getStringUUID(), prize, "CONTEST", "contest#carving#" + key, "Pumpkin-carving contest (" + count + " lanterns)", p.level().getGameTime());
        s.life.complete(p, "event");
        return "🎃 The judges counted " + count + " carved pumpkin" + (count == 1 ? "" : "s") + " at your home: +" + prize + " LC!";
    }
}
