package su.nightexpress.excellentcrates.data;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.api.crate.Reward;
import su.nightexpress.excellentcrates.config.Config;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.data.crate.GlobalCrateData;
import su.nightexpress.excellentcrates.crate.reward.RewardKey;
import su.nightexpress.excellentcrates.data.reward.RewardData;
import su.nightexpress.nightcore.manager.AbstractManager;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DataManager extends AbstractManager<CratesPlugin> {

    private final Map<String, GlobalCrateData> crateDataMap;
    private final Map<RewardKey, RewardData>   rewardLimitMap;

    private volatile boolean dataLoaded;

    public DataManager(@NotNull CratesPlugin plugin) {
        super(plugin);
        this.crateDataMap = new ConcurrentHashMap<>();
        this.rewardLimitMap = new ConcurrentHashMap<>();
    }

    @Override
    protected void onLoad() {
        this.plugin.runTaskAsync(() -> this.loadData());

        this.addAsyncTask(this::saveCrateDatas, Config.DATA_CRATE_DATA_SAVE_INTERVAL.get());
        this.addAsyncTask(this::saveRewardLimits, Config.DATA_REWARD_LIMITS_SAVE_INTERVAL.get());
    }

    @Override
    protected void onShutdown() {
        this.saveData();
        this.crateDataMap.clear();
        this.rewardLimitMap.clear();
        this.dataLoaded = false;
    }

    public void saveData() {
        this.saveCrateDatas();
        this.saveRewardLimits();
    }

    public void saveCrateDatas() {
        // Use iterator to avoid creating unnecessary intermediate collections
        boolean hasDirty = false;
        for (GlobalCrateData data : this.crateDataMap.values()) {
            if (data.isDirty()) {
                hasDirty = true;
                break;
            }
        }
        if (!hasDirty) return;

        // Collect dirty entries and atomically mark them clean
        for (Map.Entry<String, GlobalCrateData> entry : this.crateDataMap.entrySet()) {
            GlobalCrateData data = entry.getValue();
            if (data.compareAndSetDirty(true, false)) {
                this.plugin.getDataHandler().updateCrateData(data);
            }
        }
    }

    public void saveRewardLimits() {
        boolean hasDirty = false;
        for (RewardData data : this.rewardLimitMap.values()) {
            if (data.isSaveRequired()) {
                hasDirty = true;
                break;
            }
        }
        if (!hasDirty) return;

        for (RewardData data : this.rewardLimitMap.values()) {
            if (data.compareAndSetSaveRequired(true, false)) {
                this.plugin.getDataHandler().updateRewardLimit(data);
            }
        }
    }

    public void loadData() {
        this.loadCrateDatas();
        this.loadRewardLimits();

        this.dataLoaded = true;
    }

    public void loadCrateDatas() {
        this.crateDataMap.clear();

        for (GlobalCrateData data : this.plugin.getDataHandler().loadCrateDatas()) {
            this.crateDataMap.put(data.getCrateId(), data);
        }
    }

    public void loadRewardLimits() {
        this.rewardLimitMap.clear();

        for (RewardData data : this.plugin.getDataHandler().loadRewardLimits()) {
            this.addRewardLimit(data);
        }
    }



    public void handleSynchronization() {
        if (!this.dataLoaded) return;

        if (Config.isCrateDataSynchronized()) {
            this.loadCrateDatas();
        }
        if (Config.isRewardLimitsSynchronized()) {
            this.loadRewardLimits();
        }
    }

    public void handleCrateRemoval(@NotNull Crate crate) {
        if (Config.isCrateDataSynchronized()) {
            this.deleteCrateData(crate);
        }
        if (Config.isRewardLimitsSynchronized()) {
            this.deleteRewardLimits(crate);
        }
    }

    public void handleRewardRemoval(@NotNull Reward reward) {
        if (Config.isRewardLimitsSynchronized()) {
            this.deleteRewardLimits(reward);
        }
    }



    public boolean isDataLoaded() {
        return this.dataLoaded;
    }

    /**
     * Returns an unmodifiable view of crate datas. Use sparingly.
     * For read operations, access the map directly.
     */
    @NotNull
    public Set<GlobalCrateData> getCrateDatas() {
        return Set.copyOf(this.crateDataMap.values());
    }

    @Nullable
    public GlobalCrateData getCrateData(@NotNull String crateId) {
        return this.crateDataMap.get(crateId.toLowerCase());
    }

    @NotNull
    public GlobalCrateData getCrateDataOrCreate(@NotNull Crate crate) {
        GlobalCrateData data = this.crateDataMap.get(crate.getId());
        if (data != null) return data;

        GlobalCrateData fresh = GlobalCrateData.create(crate);
        this.crateDataMap.put(fresh.getCrateId(), fresh);
        this.plugin.runTaskAsync(() -> this.plugin.getDataHandler().insertCrateData(fresh));
        return fresh;
    }

    public void deleteCrateData(@NotNull Crate crate) {
        this.crateDataMap.remove(crate.getId());
        this.plugin.runTaskAsync(() -> this.plugin.getDataHandler().deleteCrateData(crate));
    }



    @NotNull
    public RewardData getRewardLimitOrCreate(@NotNull Reward reward, @Nullable Player player) {
        RewardKey key = getRewardKey(reward, player);
        RewardData limit = this.rewardLimitMap.get(key);
        if (limit != null) return limit;

        RewardData fresh = RewardData.create(reward, player);
        this.rewardLimitMap.put(key, fresh);
        this.plugin.runTaskAsync(() -> this.plugin.getDataHandler().insertRewardLimit(fresh));
        return fresh;
    }

    @Nullable
    public RewardData getRewardLimit(@NotNull Reward reward, @Nullable Player player) {
        RewardKey key = getRewardKey(reward, player);
        return this.rewardLimitMap.get(key);
    }

    @NotNull
    public Set<RewardData> getRewardLimits() {
        return Set.copyOf(this.rewardLimitMap.values());
    }

    private void addRewardLimit(@NotNull RewardData limit) {
        RewardKey key = getRewardKey(limit);
        this.rewardLimitMap.put(key, limit);
    }

    public void deleteRewardLimit(@NotNull RewardData limit) {
        this.rewardLimitMap.remove(getRewardKey(limit));
        this.plugin.runTaskAsync(() -> this.plugin.getDataHandler().deleteRewardLimit(limit));
    }

    public void deleteRewardLimits(@NotNull Crate crate) {
        String crateId = crate.getId();
        this.rewardLimitMap.keySet().removeIf(key -> key.crateId().equalsIgnoreCase(crateId));
        this.plugin.runTaskAsync(() -> this.plugin.getDataHandler().deleteRewardLimits(crate));
    }

    public void deleteRewardLimits(@NotNull Reward reward) {
        String crateId = reward.getCrate().getId();
        String rewardId = reward.getId();
        this.rewardLimitMap.keySet().removeIf(key -> key.crateId().equalsIgnoreCase(crateId) && key.rewardId().equalsIgnoreCase(rewardId));
        this.plugin.runTaskAsync(() -> this.plugin.getDataHandler().deleteRewardLimits(reward));
    }

    public void deleteRewardLimits(@NotNull UUID playerId) {
        String holder = playerId.toString();
        this.rewardLimitMap.keySet().removeIf(key -> key.holder().equalsIgnoreCase(holder));
        this.plugin.runTaskAsync(() -> this.plugin.getDataHandler().deleteRewardLimits(playerId));
    }



    @NotNull
    public static String getHolder(@NotNull Reward reward, @Nullable Player player) {
        return player == null ? reward.getCrate().getId() : player.getUniqueId().toString();
    }

    @NotNull
    public static RewardKey getRewardKey(@NotNull Reward reward, @Nullable Player player) {
        Crate crate = reward.getCrate();
        String holder = getHolder(reward, player);

        return new RewardKey(holder, crate.getId(), reward.getId());
    }

    @NotNull
    public static RewardKey getRewardKey(@NotNull RewardData limit) {
        return new RewardKey(limit.getHolder(), limit.getCrateId(), limit.getRewardId());
    }
}
