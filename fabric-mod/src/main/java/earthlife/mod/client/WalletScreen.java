package earthlife.mod.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Wallet + travel booking. Buttons send /earthlife commands, so all money logic stays on the server
 * (the client can't mint LifeCoins or skip the ledger).
 */
public final class WalletScreen extends Screen {
    private EditBox flight, seat;

    public WalletScreen() { super(Component.literal("EarthLife Wallet")); }

    @Override protected void init() {
        int cx = width / 2, y = height / 2 - 60;
        addRenderableWidget(Button.builder(Component.literal("Claim hourly (150 LC)"), b -> cmd("earthlife claim hourly")).bounds(cx - 155, y, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Claim daily (700 LC)"), b -> cmd("earthlife claim daily")).bounds(cx + 5, y, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Claim weekly bonus"), b -> cmd("earthlife claim weekly")).bounds(cx - 155, y + 24, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Show flights"), b -> cmd("earthlife flights")).bounds(cx + 5, y + 24, 150, 20).build());
        flight = new EditBox(font, cx - 155, y + 64, 150, 20, Component.literal("Flight id"));
        flight.setHint(Component.literal("Flight id, e.g. EL101-0"));
        seat = new EditBox(font, cx + 5, y + 64, 70, 20, Component.literal("Seat"));
        seat.setHint(Component.literal("Seat 3C"));
        addRenderableWidget(flight); addRenderableWidget(seat);
        addRenderableWidget(Button.builder(Component.literal("Seats"), b -> cmd("earthlife seats \"" + flight.getValue() + "\"")).bounds(cx + 80, y + 64, 75, 20).build());
        int y2 = y + 120;
        addRenderableWidget(Button.builder(Component.literal("Job: clock in"), b -> cmd("earthlife job clockin")).bounds(cx - 155, y2, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Clock out"), b -> cmd("earthlife job clockout")).bounds(cx - 50, y2, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("My tasks"), b -> cmd("earthlife job tasks")).bounds(cx + 55, y2, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Time: real"), b -> cmd("earthlife time realtime")).bounds(cx - 155, y2 + 24, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Time: 2h days"), b -> cmd("earthlife time speed 120")).bounds(cx - 50, y2 + 24, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Time: vanilla"), b -> cmd("earthlife time vanilla")).bounds(cx + 55, y2 + 24, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Buy ticket"), b -> cmd("earthlife book \"" + flight.getValue() + "\" " + seat.getValue())).bounds(cx - 75, y + 92, 150, 20).build());
    }

    private void cmd(String c) {
        if (minecraft != null && minecraft.player != null) minecraft.player.connection.sendCommand(c);
    }

    @Override public void render(GuiGraphics g, int mx, int my, float d) {
        super.render(g, mx, my, d);
        var w = EarthLifeClient.wallet;
        String bal = w == null ? "…" : String.format("%,d LC", w.balance());
        g.drawCenteredString(font, "LifeCoins balance: " + bal, width / 2, height / 2 - 90, 0xFFFFD54F);
        if (w != null) {
            long s = w.ticksUntilHourly() / 20;
            g.drawCenteredString(font, w.hourlyReady() > 0 ? "Hourly reward ready!" : String.format("Next hourly reward in %d:%02d of active play", s / 60, s % 60), width / 2, height / 2 - 76, 0xFFFFFFFF);
        }
        g.drawCenteredString(font, "LifeCoins are a fictional in-game currency. They can't be bought or cashed out.", width / 2, height / 2 + 112, 0xFF9E9E9E);
    }

    @Override public boolean isPauseScreen() { return false; }
}
