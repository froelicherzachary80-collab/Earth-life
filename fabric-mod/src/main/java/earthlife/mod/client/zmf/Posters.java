package earthlife.mod.client.zmf;

import com.mojang.blaze3d.platform.NativeImage;
import earthlife.mod.EarthLifeMod;
import earthlife.mod.client.cinema.MediaLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Film posters from the player's Jellyfin servers (downloaded once per session, shown on screens, signs and boards). */
public final class Posters {
    private Posters() {}

    record Entry(Identifier id, float aspect) {}
    static final Map<String, Entry> loaded = new HashMap<>();
    static final Set<String> pending = new HashSet<>(), failed = new HashSet<>();

    /** Poster texture for a film source, or null while loading / unavailable. Render thread only. */
    public static Entry get(String source) {
        if (source == null || !source.startsWith("jellyfin:")) return null;
        Entry e = loaded.get(source);
        if (e != null || pending.contains(source) || failed.contains(source) || loaded.size() > 96) return e;
        pending.add(source);
        CompletableFuture.supplyAsync(() -> MediaLibrary.poster(source)).thenAccept(bytes -> Minecraft.getInstance().execute(() -> {
            pending.remove(source);
            if (bytes == null) { failed.add(source); return; }
            try {
                NativeImage img = NativeImage.read(bytes);
                float aspect = img.getWidth() / (float) Math.max(1, img.getHeight());
                DynamicTexture t = new DynamicTexture(() -> "ZMF poster", img);
                Identifier id = EarthLifeMod.id("zmf/poster_" + Integer.toHexString(source.hashCode()));
                Minecraft.getInstance().getTextureManager().register(id, t);
                loaded.put(source, new Entry(id, aspect));
            } catch (Exception ex) { failed.add(source); }
        }));
        return null;
    }

    public static Identifier id(Entry e) { return e == null ? null : e.id; }
    public static float aspect(Entry e) { return e == null ? 0.67f : e.aspect; }

    public static void reset() {
        for (Entry e : loaded.values()) Minecraft.getInstance().getTextureManager().release(e.id);
        loaded.clear(); pending.clear(); failed.clear();
    }
}
