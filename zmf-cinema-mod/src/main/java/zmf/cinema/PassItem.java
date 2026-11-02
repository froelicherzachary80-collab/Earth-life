// STANDALONE
package zmf.cinema;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * ZMF Cinema Pass - every player gets one the first time they join a world. Use it to be taken to the doors of the
 * nearest multiplex (if none stands nearby yet, one is built and you arrive when it opens). Sneak + use takes you back
 * to where you used it. While you hold it the HUD shows the way and distance to the nearest multiplex.
 */
public final class PassItem extends Item {
    public PassItem(Item.Properties p) { super(p); }

    @Override public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp && MultiplexService.get() != null) {
            String msg = sp.isShiftKeyDown() ? MultiplexService.get().passReturn(sp) : MultiplexService.get().usePass(sp);
            if (msg != null) sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(msg));
        }
        return InteractionResult.SUCCESS;
    }
}
