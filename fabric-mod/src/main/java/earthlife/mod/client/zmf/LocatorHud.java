package earthlife.mod.client.zmf;

import earthlife.mod.cinema.CinemaContent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Locale;

/** Cinema Locator: while you hold it, an arrow, the distance and the name of the nearest multiplex at the top of the screen. */
public final class LocatorHud {
    private LocatorHud() {}

    public static void draw(GuiGraphicsExtractor g, Minecraft mc) {
        if (mc.player == null || mc.gui.hud.isHidden()) return;
        if (!mc.player.getMainHandItem().is(CinemaContent.CINEMA_LOCATOR) && !mc.player.getOffhandItem().is(CinemaContent.CINEMA_LOCATOR)) return;
        var n = ZmfState.nearest;
        String txt;
        if (n == null || System.currentTimeMillis() - ZmfState.nearestAtMs > 3000) txt = "🎬 Searching...";
        else if (!n.found()) txt = "🎬 No multiplex nearby";
        else {
            double dx = n.x() - mc.player.getX(), dz = n.z() - mc.player.getZ(), d = Math.hypot(dx, dz);
            double bearing = Math.toDegrees(Math.atan2(-dx, dz));
            double rel = ((bearing - mc.player.getYRot()) % 360 + 540) % 360 - 180;
            String arrow = Math.abs(rel) < 22 ? "↑" : Math.abs(rel) > 158 ? "↓" : rel > 0 ? (rel < 68 ? "↗" : rel < 112 ? "→" : "↘") : (rel > -68 ? "↖" : rel > -112 ? "←" : "↙");
            txt = String.format(Locale.ROOT, "%s  🎬 %s · %d screens · %s%s", arrow, n.name(), n.rooms(), d >= 1000 ? String.format(Locale.ROOT, "%.1f km", d / 1000) : Math.round(d) + " m",
                    n.built() ? "" : " · opens as you approach");
        }
        int w = mc.getWindow().getGuiScaledWidth();
        g.fill(w / 2 - mc.font.width(txt) / 2 - 4, 20, w / 2 + mc.font.width(txt) / 2 + 4, 32, 0x90000000);
        g.text(mc.font, txt, w / 2 - mc.font.width(txt) / 2, 22, 0xFFFFD54F, true);
    }
}
