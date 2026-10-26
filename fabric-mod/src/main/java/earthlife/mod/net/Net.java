package earthlife.mod.net;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** Sends EarthLife packets only to clients that have EarthLife installed (vanilla clients can still join a server). */
public final class Net {
    private Net() {}

    public static boolean send(ServerPlayer p, CustomPacketPayload payload) {
        if (!ServerPlayNetworking.canSend(p, payload.type())) return false;
        ServerPlayNetworking.send(p, payload);
        return true;
    }

    public static boolean hasClientMod(ServerPlayer p) { return ServerPlayNetworking.canSend(p, Payloads.LifeState.TYPE); }
}
