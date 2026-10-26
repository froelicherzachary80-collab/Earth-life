package earthlife.mod.client;

import com.mojang.blaze3d.platform.NativeImage;
import earthlife.core.npc.SkinGenerator;
import earthlife.mod.npc.NpcEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Draws residents with the vanilla player model (classic arms) and a skin painted on the client from the
 * resident's synced seed, so the server never sends textures and every client sees the same face.
 */
public final class NpcRenderer extends HumanoidMobRenderer<NpcEntity, NpcRenderer.State, HumanoidModel<NpcRenderer.State>> {
    public static final class State extends HumanoidRenderState { long skin; int uniform; }

    private static final Map<Long, Identifier> TEXTURES = new HashMap<>();

    /** Vanilla player mesh (classic arms, with jacket/sleeve/pant overlays as child parts) driven by the humanoid model. */
    public NpcRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER)), 0.5f);
    }

    @Override public State createRenderState() { return new State(); }

    @Override public void extractRenderState(NpcEntity e, State s, float partial) {
        super.extractRenderState(e, s, partial);
        s.skin = e.skinSeed();
        s.uniform = e.uniform().ordinal();
    }

    @Override public Identifier getTextureLocation(State s) {
        long key = s.skin * 31 + s.uniform;
        return TEXTURES.computeIfAbsent(key, k -> {
            try {
                byte[] png = SkinGenerator.png(s.skin, SkinGenerator.Uniform.values()[s.uniform]);
                NativeImage img = NativeImage.read(new ByteArrayInputStream(png));
                Identifier id = Identifier.fromNamespaceAndPath("earthlife", "npc_skin/" + Long.toHexString(k));
                Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "earthlife npc skin", img));
                return id;
            } catch (Exception ex) {
                return Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png");
            }
        });
    }
}
