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

    int boxTop() { return Math.max(4, height / 2 - 74 - Math.max(0, (d.options().size() - 4) * 6)); }

    @Override protected void init() {
        int n = d.options().size();
        boolean twoCols = height < 300 && n > 4;           // small windows: options in two columns so all fit
        int full = Math.min(340, width - 30), w = twoCols ? (full - 4) / 2 : full, x0 = (width - full) / 2;
        int y = boxTop() + 64;
        for (int i = 0; i < n; i++) {
            final int choice = i;
            String opt = d.options().get(i);
            int bx = twoCols ? x0 + (i % 2) * (w + 4) : x0, by = twoCols ? y + (i / 2) * 20 : y + i * 20;
            addRenderableWidget(Button.builder(Component.literal(opt), b -> {
                EarthLifeClient.command("earthlife talk " + d.entityId() + " " + choice);
                if (opt.equals("Goodbye") || opt.startsWith("Any jobs")) onClose();
            }).bounds(bx, by, w, 18).build());
        }
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        int w = Math.min(340, width - 30), x0 = (width - w) / 2, top = boxTop();
        g.fill(x0 - 6, top - 6, x0 + w + 6, top + 58, 0xD0101418);
        g.text(font, "§b" + d.npcName(), x0, top, 0xFFFFFFFF, false);
        g.text(font, d.relation(), x0, top + 11, 0xFF90A4AE, false);
        g.textWithWordWrap(font, Component.literal("“" + d.line() + "”"), x0, top + 26, w, 0xFFFFFFFF);
        super.extractRenderState(g, mx, my, delta);
    }
}
