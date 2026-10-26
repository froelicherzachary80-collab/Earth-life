package earthlife.mod.cinema;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * The big screen of one auditorium: positioned at the bottom-centre of the picture, facing the seats (yaw). The client
 * draws the film decoded on that player's computer (or the theater's idle slide) on it.
 */
public final class CinemaScreenEntity extends Entity {
    private static final EntityDataAccessor<String> KEY = SynchedEntityData.defineId(CinemaScreenEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> LABEL = SynchedEntityData.defineId(CinemaScreenEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Float> W = SynchedEntityData.defineId(CinemaScreenEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> H = SynchedEntityData.defineId(CinemaScreenEntity.class, EntityDataSerializers.FLOAT);

    public CinemaScreenEntity(EntityType<? extends CinemaScreenEntity> t, Level level) { super(t, level); noPhysics = true; }

    void configure(String key, float w, float h, String label) {
        entityData.set(KEY, key); entityData.set(W, w); entityData.set(H, h); entityData.set(LABEL, label);
    }

    public String key() { return entityData.get(KEY); }
    public String label() { return entityData.get(LABEL); }
    public float screenWidth() { return entityData.get(W); }
    public float screenHeight() { return entityData.get(H); }

    @Override protected void defineSynchedData(SynchedEntityData.Builder b) { b.define(KEY, "").define(LABEL, "").define(W, 8f).define(H, 3.6f); }
    @Override public boolean hurtServer(ServerLevel level, DamageSource s, float a) { return false; }
    @Override protected void readAdditionalSaveData(ValueInput in) {}
    @Override protected void addAdditionalSaveData(ValueOutput out) {}
    @Override public boolean shouldBeSaved() { return false; }
    @Override public boolean isPickable() { return false; }
}
