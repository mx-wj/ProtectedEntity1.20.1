package com.mx_wj.protectedentity.entity;

import com.mx_wj.protectedentity.health.HealthCalculator;
import com.mx_wj.protectedentity.health.HealthFactory;
import com.mx_wj.protectedentity.network.ModNetwork;
import java.security.SecureRandom;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraftforge.common.ForgeHooks;

final class HiddenProtectedEntity extends ProtectedEntity {
    private static final long DAMAGE_COOLDOWN_NANOS = 1_000_000_000L;
    private static final String INTERNAL_ID_TAG = "ProtectedEntityInternalId";
    private static final String HEALTH_TAG = "ProtectedEntityHealth";
    private static final char[] ID_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom ID_RANDOM = new SecureRandom();
    private static final EntityDataAccessor<Integer> ATTACK_SEQUENCE =
            SynchedEntityData.defineId(HiddenProtectedEntity.class, EntityDataSerializers.INT);
    private String internalId = "";
    private boolean processingDamage;
    private boolean hasAcceptedDamage;
    private long lastAcceptedDamageNanos;
    private int clientAttackStartTick = Integer.MIN_VALUE;
    private final ProtectedEntityCombatAI combatAI = new ProtectedEntityCombatAI(this);

    private final ServerBossEvent bossEvent = new ServerBossEvent(
            Component.translatable("bossbar.protectedentity", this.getId(), this.getDisplayName()),
            BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);

    public HiddenProtectedEntity(EntityType<? extends ProtectedEntity> type, Level level) {
        super(type, level);
        if (!level.isClientSide) {
            this.internalId = createInternalId();
            HealthFactory.setHealth(this.internalId, HealthCalculator.MAX_HEALTH, false);
        }
    }

    private static String createInternalId() {
        StringBuilder id = new StringBuilder(24);
        for (int i = 0; i < 24; i++) {
            id.append(ID_ALPHABET[ID_RANDOM.nextInt(ID_ALPHABET.length)]);
        }
        return id.toString();
    }

    public String getInternalId() {
        return this.internalId == null ? "" : this.internalId;
    }

    public void applyClientHealthSync(String internalId, float health) {
        if (!HealthFactory.isAuthorizedCaller()
                || !this.level().isClientSide || internalId == null || internalId.isEmpty()) {
            return;
        }
        String previousId = this.getInternalId();
        if (!previousId.isEmpty() && !previousId.equals(internalId)) {
            HealthFactory.removeHealth(previousId, true);
        }
        this.internalId = internalId;
        HealthFactory.setHealth(internalId, health, true);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString(INTERNAL_ID_TAG, this.getInternalId());
        tag.putFloat(HEALTH_TAG, HealthFactory.getHealth(this.getInternalId(), this.level().isClientSide));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        String previousId = this.getInternalId();
        String savedId = tag.getString(INTERNAL_ID_TAG);
        if (!savedId.isEmpty() && !savedId.equals(previousId)) {
            if (!previousId.isEmpty()) {
                HealthFactory.removeHealth(previousId, this.level().isClientSide);
            }
            this.internalId = savedId;
        }
        float savedHealth = tag.contains(HEALTH_TAG, Tag.TAG_ANY_NUMERIC)
                ? tag.getFloat(HEALTH_TAG) : HealthCalculator.MAX_HEALTH;
        String internalId = this.getInternalId();
        if (!internalId.isEmpty()) {
            HealthFactory.setHealth(internalId, savedHealth, this.level().isClientSide);
        }
        super.readAdditionalSaveData(tag);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(ATTACK_SEQUENCE, 0);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (this.level().isClientSide && ATTACK_SEQUENCE.equals(key)
                && this.entityData.get(ATTACK_SEQUENCE) != 0) {
            this.clientAttackStartTick = this.tickCount;
        }
    }

    public void playAttackAnimation() {
        this.swing(InteractionHand.MAIN_HAND, true);
        this.entityData.set(ATTACK_SEQUENCE, this.entityData.get(ATTACK_SEQUENCE) + 1);
    }

    @Override
    public void attackAfterTeleport(LivingEntity target) {
        this.combatAI.attackAfterTeleport(target);
    }

    void onAttackHit(LivingEntity target) {
        this.combatAI.onAttackHit(target);
    }

    public float getVisibleAttackAnimation(float partialTicks) {
        float elapsed = this.tickCount + partialTicks - (float)this.clientAttackStartTick;
        return elapsed >= 0.0F && elapsed < 8.0F ? elapsed / 8.0F : 0.0F;
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(7, new IdleStrollGoal(this));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide && this.isAlive()) {
            this.combatAI.tick();
        }
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        this.bossEvent.setProgress(HealthCalculator.progress(
                HealthFactory.getHealthValue(this.getInternalId(), false), this.getMaxHealth()));
        this.bossEvent.addPlayer(player);
        ModNetwork.sendToPlayer(player, this);
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        this.bossEvent.removePlayer(player);
    }

    @Override
    public float getHealth() {
        String internalId = this.getInternalId();
        return internalId.isEmpty() ? 0.0F : HealthFactory.getHealth(internalId, this.level().isClientSide);
    }

    @Override
    public float getMaxHealth() {
        return HealthCalculator.MAX_HEALTH;
    }

    @Override
    public final boolean hurt(DamageSource source, float amount) {
        if (!this.level().isClientSide && amount > 0.0F && !this.isInvulnerableTo(source)) {
            this.combatAI.onAttackedBy(source);
        }
        if (this.processingDamage || this.isDamageCoolingDown()) {
            return false;
        }
        return super.hurt(source, amount);
    }

    @Override
    protected final synchronized void actuallyHurt(DamageSource source, float amount) {
        if (this.level().isClientSide || this.processingDamage || this.isDamageCoolingDown()) {
            return;
        }
        this.processingDamage = true;
        try {
            if (this.isInvulnerableTo(source)) {
                return;
            }
            float damage = HealthCalculator.reducedDamage(amount);
            if (!Float.isFinite(damage) || damage <= 0.0F) {
                return;
            }
            damage = this.getDamageAfterArmorAbsorb(source, damage);
            damage = this.getDamageAfterMagicAbsorb(source, damage);
            if (!Float.isFinite(damage) || damage <= 0.0F) {
                return;
            }
            float absorption = this.getAbsorptionAmount();
            float healthDamage = Math.max(damage - absorption, 0.0F);
            float absorbedDamage = damage - healthDamage;
            this.setAbsorptionAmount(absorption - absorbedDamage);
            if (absorbedDamage > 0.0F && absorbedDamage < Float.MAX_VALUE
                    && source.getEntity() instanceof ServerPlayer player) {
                player.awardStat(Stats.DAMAGE_DEALT_ABSORBED, Math.round(absorbedDamage * 10.0F));
            }
            if (!Float.isFinite(healthDamage) || healthDamage <= 0.0F) {
                return;
            }
            float healthBeforeHit = this.getHealth();
            float limitedHealth = HealthCalculator.limitDamage(healthBeforeHit, healthBeforeHit - healthDamage);
            if (limitedHealth < healthBeforeHit) {
                float appliedDamage = healthBeforeHit - limitedHealth;
                this.getCombatTracker().recordDamage(source, appliedDamage);
                String internalId = this.getInternalId();
                if (!internalId.isEmpty()) {
                    HealthFactory.setHealth(internalId, limitedHealth, false);
                    if (this.isAddedToWorld()) {
                        ModNetwork.sendToTracking(this);
                    }
                }
                this.setAbsorptionAmount(this.getAbsorptionAmount() - appliedDamage);
                this.gameEvent(GameEvent.ENTITY_DAMAGE);
                if (this.getHealth() < healthBeforeHit) {
                    this.lastAcceptedDamageNanos = System.nanoTime();
                    this.hasAcceptedDamage = true;
                    this.combatAI.onAttackedBy(source);
                }
            }
        } finally {
            this.processingDamage = false;
        }
    }

    private boolean isDamageCoolingDown() {
        return this.hasAcceptedDamage
                && System.nanoTime() - this.lastAcceptedDamageNanos < DAMAGE_COOLDOWN_NANOS;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("entity.protectedentity.protected_entity");
    }

    @Override
    public boolean shouldShowName() {
        return true;
    }

    @Override
    public boolean hasCustomName() {
        return true;
    }

    @Override
    @Deprecated
    public final void setHealth(float health) {}
}
