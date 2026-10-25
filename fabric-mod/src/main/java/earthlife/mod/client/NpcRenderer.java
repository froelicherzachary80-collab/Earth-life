package earthlife.mod.client;

import com.mojang.blaze3d.platform.NativeImage;
import earthlife.core.npc.SkinGenerator;
import earthlife.mod.npc.NpcEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Draws residents with the vanilla player model (classic arms) and a skin painted on the client from the
 * resident's synced seed, so the server never sends textures and every client sees the same face.
 * UNVERIFIED: renderer generics follow the 1.21.4+ render-state API; PlayerModel's constructor/state type and
 * DynamicTexture's constructor changed in 1.21.5 - adjust at first compile.
 */
public final class NpcRenderer extends HumanoidMobRenderer<NpcEntity, NpcRenderer.State, HumanoidModel<NpcRenderer.State>> {
    public static final class State extends HumanoidRenderState { long skin; int uniform; }

    private static final Map<Long, ResourceLocation> TEXTURES = new HashMap<>();

    @SuppressWarnings({"unchecked", "rawtypes"})
    public NpcRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, (HumanoidModel) new PlayerModel(ctx.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
    }

    @Override public State createRenderState() { return new State(); }

    @Override public void extractRenderState(NpcEntity e, State s, float partial) {
        super.extractRenderState(e, s, partial);
        s.skin = e.skinSeed();
        s.uniform = e.uniform().ordinal();
    }

    @Override public ResourceLocation getTextureLocation(State s) {
        long key = s.skin * 31 + s.uniform;
        return TEXTURES.computeIfAbsent(key, k -> {
            try {
                byte[] png = SkinGenerator.png(s.skin, SkinGenerator.Uniform.values()[s.uniform]);
                NativeImage img = NativeImage.read(new ByteArrayInputStream(png));
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath("earthlife", "npc_skin/" + Long.toHexString(k));
                Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "earthlife npc skin", img));
                return id;
            } catch (Exception ex) {
                return ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png");
            }
        });
    }
}
