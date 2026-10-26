package earthlife.mod.zmf;

import earthlife.mod.McCompat;
import earthlife.mod.cinema.CinemaContent;
import earthlife.mod.cinema.CinemaService;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/**
 * Tickets and snacks in ordinary worlds cost emeralds (configurable, 0 = free); creative players and worlds with free
 * tickets pay nothing. No real money is ever involved. Tickets carry the room and how long they are valid, so the door
 * can scan them.
 */
public final class ZmfEconomy {
    private ZmfEconomy() {}

    public static boolean free(ServerPlayer p) {
        return ZmfConfig.get().freeInCreative && (p.isCreative() || p.isSpectator());
    }

    public static int ticketPrice(CinemaService.Aud a, String format) {
        var c = ZmfConfig.get();
        boolean premium = a.motion() || a.has("IMAX") || a.has("ScreenX") || (format != null && !"2D".equals(format));
        return premium ? c.premiumEmeralds : c.ticketEmeralds;
    }

    public static String label(int price, ServerPlayer p) {
        if (price <= 0 || (p != null && free(p))) return "free";
        return price + (price == 1 ? " emerald" : " emeralds");
    }

    static int count(ServerPlayer p) {
        int n = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) { ItemStack s = inv.getItem(i); if (s.is(Items.EMERALD)) n += s.getCount(); }
        return n;
    }

    /** Takes the price in emeralds (or nothing when free) and returns what was paid; throws if the player can't pay. */
    public static String pay(ServerPlayer p, int price, String what) throws Exception {
        if (price <= 0 || free(p)) return "free";
        int have = count(p);
        if (have < price) throw new Exception("You need " + label(price, null) + " for that (you have " + have + "). Trade or mine for emeralds - or play in creative.");
        int left = price;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(Items.EMERALD)) continue;
            int take = Math.min(left, s.getCount());
            s.shrink(take); left -= take;
        }
        return label(price, null);
    }

    public static boolean mayStartPrivateShowing(ServerPlayer p) {
        var mode = ZmfConfig.get().privateShowings;
        if ("everyone".equalsIgnoreCase(mode)) return true;
        if (McCompat.isOp(p)) return true;
        var server = p.level().getServer();
        return "singleplayer".equalsIgnoreCase(mode) && server != null && server.isSingleplayer();
    }

    /** Marks a ticket item with the room it admits to and until when (epoch ms). */
    public static void tagTicket(ItemStack t, String audKey, long validUntilMs) {
        CompoundTag tag = new CompoundTag();
        tag.putString("zmf_room", audKey);
        tag.putLong("zmf_until", validUntilMs);
        t.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /** True if the player carries a valid ticket for this room right now. */
    public static boolean hasTicket(ServerPlayer p, String audKey, long now) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(CinemaContent.MOVIE_TICKET)) continue;
            CustomData d = s.get(DataComponents.CUSTOM_DATA);
            if (d == null) continue;
            CompoundTag tag = d.copyTag();
            if (audKey.equals(tag.getStringOr("zmf_room", "")) && tag.getLongOr("zmf_until", 0L) >= now) return true;
        }
        return false;
    }
}
