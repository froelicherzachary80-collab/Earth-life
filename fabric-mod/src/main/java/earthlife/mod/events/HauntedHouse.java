package earthlife.mod.events;

import com.mojang.math.Transformation;
import earthlife.core.life.PlayerLife;
import earthlife.core.world.Features;
import earthlife.core.world.TileData;
import earthlife.core.world.TileKey;
import earthlife.core.world.Towns;
import earthlife.mod.EarthLifeServices;
import earthlife.mod.McCompat;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Interaction;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * Halloween mini-game: in each town one house is "haunted" during Halloween-week evenings. Spooky sounds play near
 * it, and three glowing jack o'lanterns are hidden in and around it - find them all (right-click) for candy and
 * LifeCoins, once per year. Uses display + interaction entities, so no blocks of the world are changed.
 */
public final class HauntedHouse {
    record House(String town, double cx, double cz, List<double[]> spots) {}

    private House active;
    private final List<Entity> entities = new ArrayList<>();
    private final Map<UUID, Integer> pumpkinIndex = new HashMap<>();

    public Optional<double[]> location() { return active == null ? Optional.empty() : Optional.of(new double[]{active.cx, active.cz}); }
    public String town() { return active == null ? null : active.town; }

    public void tick(MinecraftServer server) {
        if (server.getTickCount() % 40 != 0) return;
        var s = EarthLifeServices.get();
        ServerLevel level = server.overworld();
        boolean on = Halloween.isSeason(s.time.clock().zone()) && Halloween.isEvening(McCompat.dayTime(level));
        ServerPlayer near = null;
        Towns.Town town = null;
        if (on) for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level() != level || s.towns.isEmpty()) continue;
            var g = s.projection.fromBlock(p.getX(), p.getZ());
            Towns.Town t = Towns.nearest(s.towns, g);
            if (t.centre().distanceTo(g) < t.kind().radiusM() + 300) { near = p; town = t; break; }
        }
        if (!on || near == null) { clear(); return; }
        if (active == null || !active.town.equals(town.name())) { clear(); active = choose(town); if (active != null) spawn(level); }
        if (active == null) return;
        // ambience for anyone close to the house
        for (ServerPlayer p : server.getPlayerList().getPlayers())
            if (Math.hypot(p.getX() - active.cx, p.getZ() - active.cz) < 24 && server.getTickCount() % 200 == 0)
                level.playSound(null, active.cx, p.getY() + 1, active.cz, p.getRandom().nextBoolean() ? SoundEvents.PHANTOM_AMBIENT : SoundEvents.AMBIENT_CAVE.value(),
                        SoundSource.AMBIENT, 0.8f, 0.7f + p.getRandom().nextFloat() * 0.3f);
    }

    /** A real (mapped) house near the town centre, picked per town and year so everyone gets the same one. */
    House choose(Towns.Town town) {
        var s = EarthLifeServices.get();
        var c = s.projection.toBlock(town.centre().lat(), town.centre().lon());
        int year = Halloween.today(s.time.clock().zone()).getYear();
        List<Features.Building> houses = new ArrayList<>();
        for (Features.Building b : s.features.buildings) {
            String k = b.kind() == null ? "" : b.kind();
            if (!(k.equals("house") || k.equals("detached") || k.equals("residential") || k.equals("yes"))) continue;
            var p = b.footprint().get(0);
            double d = Math.hypot(p.x() - c.x(), p.z() - c.z());
            if (d > 80 && d < 500 && b.footprint().size() >= 4) houses.add(b);
        }
        if (houses.isEmpty()) return null;
        houses.sort(Comparator.comparing(Features.Building::id));
        Features.Building b = houses.get(Math.floorMod(Objects.hash(town.name(), year), houses.size()));
        var o = earthlife.core.world.BuildingStyle.obb(b.footprint());
        List<double[]> spots = new ArrayList<>();
        double[][] uv = {{-o.halfU() * 0.4, 0}, {o.halfU() * 0.4, -o.halfV() * 0.3}, {0, o.halfV() + 2.5}};   // two inside, one in the yard
        for (double[] q : uv) spots.add(new double[]{o.cx() + q[0] * o.ux() - q[1] * o.uz(), o.cz() + q[0] * o.uz() + q[1] * o.ux()});
        return new House(town.name(), o.cx(), o.cz(), spots);
    }

    void spawn(ServerLevel level) {
        var s = EarthLifeServices.get();
        for (int i = 0; i < active.spots.size(); i++) {
            double[] q = active.spots.get(i);
            int x = (int) Math.floor(q[0]), z = (int) Math.floor(q[1]);
            level.getChunk(x >> 4, z >> 4);
            TileData t = s.tiles.request(TileKey.containing(s.region, x, z, 0)).join();
            int y = t.terrainY[t.idx(x - t.key.minX(), z - t.key.minZ())] + 1;     // floor (inside) or ground (yard)
            Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.TRIGGERED);
            Interaction hit = EntityTypes.INTERACTION.create(level, EntitySpawnReason.TRIGGERED);
            if (d == null || hit == null) continue;
            d.setBlockState(McCompat.block("minecraft:jack_o_lantern"));
            d.setTransformation(new Transformation(new Vector3f(-0.3f, 0, -0.3f), new Quaternionf(), new Vector3f(0.6f), new Quaternionf()));
            d.setPos(x + 0.5, y, z + 0.5);
            hit.setPos(x + 0.5, y, z + 0.5);
            earthlife.mod.McCompat.spawnTemporary(level, d);
            earthlife.mod.McCompat.spawnTemporary(level, hit);
            entities.add(d); entities.add(hit);
            pumpkinIndex.put(hit.getUUID(), i);
        }
    }

    /** UseEntityCallback: true if the entity was one of our pumpkins. */
    public boolean onInteract(ServerPlayer p, Entity e) {
        Integer i = pumpkinIndex.get(e.getUUID());
        if (i == null || active == null) return false;
        var s = EarthLifeServices.get();
        PlayerLife life = s.life.life(p);
        int year = Halloween.today(s.time.clock().zone()).getYear();
        String key = year + ":haunted:" + active.town + ":" + i;
        if (!life.contests.add(key)) { p.sendOverlayMessage(Component.literal("You already found this pumpkin.")); return true; }
        life.markDirty();
        long found = life.contests.stream().filter(k -> k.startsWith(year + ":haunted:" + active.town + ":")).count();
        p.level().playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.WITCH_CELEBRATE, SoundSource.AMBIENT, 1f, 1f);
        if (found >= active.spots.size() && life.contests.add(year + ":haunted:" + active.town)) {
            try { s.ledger.credit(p.getStringUUID(), 30, "CONTEST", "haunted#" + year + "#" + active.town, "Haunted house, " + active.town, p.level().getGameTime()); }
            catch (Exception ignored) {}
            p.addItem(Halloween.candy(3, new Random()));
            life.candy += 3;
            s.life.complete(p, "event");
            p.sendSystemMessage(Component.literal("§6You found all the hidden pumpkins in the haunted house! +30 LC and 3 candy."));
        } else p.sendSystemMessage(Component.literal("§6A hidden pumpkin! (" + found + "/" + active.spots.size() + ") Keep searching the haunted house..."));
        return true;
    }

    public String progress(ServerPlayer p) {
        if (active == null) return null;
        var s = EarthLifeServices.get();
        int year = Halloween.today(s.time.clock().zone()).getYear();
        long found = s.life.life(p).contests.stream().filter(k -> k.startsWith(year + ":haunted:" + active.town + ":")).count();
        return found + "/" + active.spots.size();
    }

    public void clear() {
        entities.forEach(Entity::discard);
        entities.clear();
        pumpkinIndex.clear();
        active = null;
    }
}
