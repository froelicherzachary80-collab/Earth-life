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
    /** ZMF rendering data: "kind=screen;fmt=IMAX+3D;box=...;side=..;depth=..;floor=..;ceil=..;curved=1" (empty for plain screens). */
    private static final EntityDataAccessor<String> DATA = SynchedEntityData.defineId(CinemaScreenEntity.class, EntityDataSerializers.STRING);

    public CinemaScreenEntity(EntityType<? extends CinemaScreenEntity> t, Level level) { super(t, level); noPhysics = true; }

    public void configure(String key, float w, float h, String label) {
        entityData.set(KEY, key); entityData.set(W, w); entityData.set(H, h); entityData.set(LABEL, label);
    }

    public void data(String d) { entityData.set(DATA, d == null ? "" : d); }
    public String data() { return entityData.get(DATA); }

    /** One value of the data string ("" if absent). */
    public String dataValue(String name) {
        for (String kv : data().split(";")) { int i = kv.indexOf('='); if (i > 0 && kv.substring(0, i).equals(name)) return kv.substring(i + 1); }
        return "";
    }

    public String key() { return entityData.get(KEY); }
    public String label() { return entityData.get(LABEL); }
    public float screenWidth() { return entityData.get(W); }
    public float screenHeight() { return entityData.get(H); }

    @Override protected void defineSynchedData(SynchedEntityData.Builder b) { b.define(KEY, "").define(LABEL, "").define(W, 8f).define(H, 3.6f).define(DATA, ""); }
    @Override public boolean hurtServer(ServerLevel level, DamageSource s, float a) { return false; }
    @Override protected void readAdditionalSaveData(ValueInput in) {}
    @Override protected void addAdditionalSaveData(ValueOutput out) {}
    @Override public boolean shouldBeSaved() { return false; }
    @Override public boolean isPickable() { return false; }
}
