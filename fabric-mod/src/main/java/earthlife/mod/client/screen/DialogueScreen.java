package earthlife.mod.client.screen;

import earthlife.mod.client.EarthLifeClient;
import earthlife.mod.net.Payloads;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Conversation with a resident: their line on top, your choices as buttons. */
public final class DialogueScreen extends Screen {
    private final Payloads.Dialogue d;

    public DialogueScreen(Payloads.Dialogue d) { super(Component.literal(d.npcName())); this.d = d; }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        int w = Math.min(320, width - 40), y = height / 2 - 4;
        for (int i = 0; i < d.options().size(); i++) {
            final int choice = i;
            String opt = d.options().get(i);
            addRenderableWidget(Button.builder(Component.literal(opt), b -> {
                EarthLifeClient.command("earthlife talk " + d.entityId() + " " + choice);
                if (opt.equals("Goodbye") || opt.startsWith("Where can I find a job")) onClose();
            }).bounds((width - w) / 2, y, w, 18).build());
            y += 20;
        }
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        int w = Math.min(340, width - 30), x0 = (width - w) / 2, top = height / 2 - 74;
        g.fill(x0 - 6, top - 6, x0 + w + 6, height / 2 - 8, 0xD0101418);
        g.text(font, "§b" + d.npcName(), x0, top, 0xFFFFFFFF, false);
        g.text(font, d.relation(), x0, top + 11, 0xFF90A4AE, false);
        g.textWithWordWrap(font, Component.literal("“" + d.line() + "”"), x0, top + 26, w, 0xFFFFFFFF);
        super.extractRenderState(g, mx, my, delta);
    }
}
