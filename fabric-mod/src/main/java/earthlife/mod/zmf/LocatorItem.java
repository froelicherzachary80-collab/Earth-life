package earthlife.mod.zmf;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * Cinema Locator: while you hold it, the HUD shows the direction and distance to the nearest multiplex (generated or
 * not yet built - it appears when you get close). Right-click: the same in chat. Sneak + right-click: a map with the
 * multiplex marked on it.
 */
public final class LocatorItem extends Item {
    public LocatorItem(Item.Properties p) { super(p); }

    @Override public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp && MultiplexService.get() != null) {
            if (sp.isShiftKeyDown()) MultiplexService.get().giveMap(sp);
            else sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(MultiplexService.get().locateText(sp)));
        }
        return InteractionResult.SUCCESS;
    }
}
