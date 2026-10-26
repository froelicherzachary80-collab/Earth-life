package earthlife.mod.npc;

import earthlife.core.npc.*;
import earthlife.mod.EarthLifeServices;
import earthlife.mod.jobs.JobHooks;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A resident rendered with the vanilla player model and an original generated skin (see SkinGenerator), so NPCs
 * look like players. The server-side brain walks them to where their schedule says they should be (home, work,
 * lunch, the town centre) using vanilla pathfinding; long trips are hidden by despawning/respawning as players
 * move (only residents near players exist as entities). Right-click to talk; your relationship is remembered.
 * Not persisted as an entity: identity comes from the Population, so NpcManager re-creates them on demand.
 */
public final class NpcEntity extends PathfinderMob {
    static final EntityDataAccessor<Long> SKIN = SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.LONG);
    static final EntityDataAccessor<Integer> UNIFORM = SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.INT);

    private String residentId;
    private boolean traveler;           // airport passengers waiting for check-in (job task targets)

    public NpcEntity(EntityType<? extends NpcEntity> type, Level level) { super(type, level); setPersistenceRequired(); }

    public static AttributeSupplier.Builder attributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 20).add(Attributes.MOVEMENT_SPEED, 0.25).add(Attributes.FOLLOW_RANGE, 64);
    }

    @Override protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(SKIN, 0L); b.define(UNIFORM, 0);
    }

    public void bind(Npc n, boolean traveler) {
        this.residentId = n.id();
        this.traveler = traveler;
        entityData.set(SKIN, n.skinSeed());
        entityData.set(UNIFORM, traveler ? 0 : SkinGenerator.uniformFor(n.job()).ordinal());
        setCustomName(Component.literal(n.name()));
        setCustomNameVisible(false);
    }

    public long skinSeed() { return entityData.get(SKIN); }
    public SkinGenerator.Uniform uniform() { return SkinGenerator.Uniform.values()[entityData.get(UNIFORM)]; }
    public String residentId() { return residentId; }
    public boolean traveler() { return traveler; }

    @Override protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new OpenDoorGoal(this, true));
        goalSelector.addGoal(2, new ScheduleGoal(this));
        goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 6f));
        goalSelector.addGoal(4, new RandomLookAroundGoal(this));
    }

    @Override protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!(player instanceof ServerPlayer sp) || residentId == null) return InteractionResult.PASS;
        var s = EarthLifeServices.get();
        var npc = s.npc(residentId).orElse(null);
        if (npc == null) return InteractionResult.PASS;
        if (JobHooks.onNpcInteract(sp, this, npc)) return InteractionResult.SUCCESS;   // job tasks (check-in, boarding)
        try {
            long time = earthlife.mod.McCompat.dayTime(level());
            s.social.interact(sp.getStringUUID(), npc, Social.Action.GREET, time);
            s.life.complete(sp, "talk");
            DialogueService.open(sp, this, npc, s.social.line(npc, sp.getStringUUID(), sp.getName().getString(), time));
        } catch (Exception e) { earthlife.mod.EarthLifeMod.LOG.warn("dialogue", e); return InteractionResult.FAIL; }
        return InteractionResult.SUCCESS;
    }

    @Override public boolean removeWhenFarAway(double d) { return false; }       // NpcManager handles despawning
    @Override public boolean shouldBeSaved() { return false; }
    @Override protected void addAdditionalSaveData(ValueOutput out) { super.addAdditionalSaveData(out); }
    @Override protected void readAdditionalSaveData(ValueInput in) { super.readAdditionalSaveData(in); }

    /** Walk toward the schedule's current place; idle around it once there. */
    static final class ScheduleGoal extends Goal {
        private final NpcEntity mob;
        private int repath;
        ScheduleGoal(NpcEntity m) { mob = m; setFlags(java.util.EnumSet.of(Flag.MOVE)); }
        @Override public boolean canUse() { return mob.residentId != null && !mob.traveler; }
        @Override public void tick() {
            if (--repath > 0 || !(mob.level() instanceof ServerLevel)) return;
            repath = 40;
            var npc = EarthLifeServices.get().npc(mob.residentId).orElse(null);
            if (npc == null) return;
            var place = npc.placeFor(npc.activityAt(earthlife.mod.McCompat.dayTime(mob.level())));
            double dx = place.x() - mob.getX(), dz = place.z() - mob.getZ();
            double d = Math.hypot(dx, dz);
            if (d < 4) { if (mob.getRandom().nextInt(4) == 0) mob.getNavigation().moveTo(place.x() + mob.getRandom().nextInt(9) - 4, mob.getY(), place.z() + mob.getRandom().nextInt(9) - 4, 0.6); return; }
            // Vanilla navigation handles ~48 blocks; for longer trips walk toward an intermediate point.
            double step = Math.min(d, 40);
            mob.getNavigation().moveTo(mob.getX() + dx / d * step, mob.getY(), mob.getZ() + dz / d * step, 1.0);
        }
    }
}
