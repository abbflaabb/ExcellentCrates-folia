package su.nightexpress.excellentcrates.crate.impl;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.Placeholders;
import su.nightexpress.excellentcrates.api.crate.Reward;
import su.nightexpress.excellentcrates.config.Config;
import su.nightexpress.excellentcrates.config.Keys;
import su.nightexpress.excellentcrates.config.Lang;
import su.nightexpress.excellentcrates.config.Perms;
import su.nightexpress.excellentcrates.crate.cost.Cost;
import su.nightexpress.excellentcrates.crate.cost.CostTypeId;
import su.nightexpress.excellentcrates.crate.cost.entry.impl.EcoCostEntry;
import su.nightexpress.excellentcrates.crate.cost.entry.impl.KeyCostEntry;
import su.nightexpress.excellentcrates.crate.cost.type.impl.EcoCostType;
import su.nightexpress.excellentcrates.crate.cost.type.impl.KeyCostType;
import su.nightexpress.excellentcrates.crate.effect.CrateEffect;
import su.nightexpress.excellentcrates.crate.effect.EffectId;
import su.nightexpress.excellentcrates.crate.reward.RewardFactory;
import su.nightexpress.excellentcrates.data.crate.GlobalCrateData;
import su.nightexpress.excellentcrates.hologram.HologramManager;
import su.nightexpress.excellentcrates.hologram.HologramTemplate;
import su.nightexpress.excellentcrates.registry.CratesRegistries;
import su.nightexpress.excellentcrates.util.CrateUtils;
import su.nightexpress.excellentcrates.util.ItemHelper;
import su.nightexpress.excellentcrates.util.pos.WorldPos;
import su.nightexpress.nightcore.bridge.currency.Currency;
import su.nightexpress.nightcore.bridge.item.AdaptedItem;
import su.nightexpress.nightcore.config.FileConfig;
import su.nightexpress.nightcore.universalscheduler.foliaScheduler.FoliaScheduler;
import su.nightexpress.nightcore.integration.currency.EconomyBridge;
import su.nightexpress.nightcore.manager.ConfigBacked;
import su.nightexpress.nightcore.util.FileUtil;
import su.nightexpress.nightcore.util.ItemUtil;
import su.nightexpress.nightcore.util.PDCUtil;
import su.nightexpress.nightcore.util.problem.ProblemCollector;
import su.nightexpress.nightcore.util.problem.ProblemReporter;
import su.nightexpress.nightcore.util.profile.CachedProfile;
import su.nightexpress.nightcore.util.profile.PlayerProfiles;
import su.nightexpress.nightcore.util.random.Rnd;
import su.nightexpress.nightcore.util.wrapper.UniParticle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

public class Crate implements ConfigBacked {

    private final CratesPlugin plugin;
    private final Path         filePath;
    private final String       id;

    private final Set<WorldPos>                 blockPositions;
    private final Set<Milestone>                milestones;
    private final Map<String, Cost>             costMap;
    private final LinkedHashMap<String, Reward> rewardMap;

    private String      name;
    private List<String> description;
    private AdaptedItem  item;
    private boolean      itemStackable;

    private boolean previewEnabled;
    private String  previewId;
    private boolean openingEnabled;
    private String  openingId;

    private boolean openingCooldownEnabled;
    private int openingCooldownTime;
    private int openingLimitAmount;

    private boolean permissionRequired;

    private boolean milestonesRepeatable;
    private boolean     pushbackEnabled;

    private boolean hologramEnabled;
    private String  hologramTemplateId;
    private double  hologramYOffset;

    private boolean effectEnabled;
    private String      effectType;
    private UniParticle effectParticle;

    private List<String> postOpenCommands;

    private boolean dirty;

    public Crate(@NotNull CratesPlugin plugin, @NotNull Path path, @NotNull String id) {
        this.plugin = plugin;
        this.filePath = path;
        this.id = id;

        this.costMap = new LinkedHashMap<>();
        this.rewardMap = new LinkedHashMap<>();
        this.blockPositions = new HashSet<>();
        this.milestones = new HashSet<>();
        this.description = new ArrayList<>();
    }

    public void load() throws IllegalStateException {
        if (!this.hasFile()) {
            // TODO Throw
            return;
        }

        this.loadConfig().edit(this::load);
    }

    private void load(@NotNull FileConfig config) throws IllegalStateException {
        if (!config.contains("_dataver")) {
            config.set("_dataver", 600);

            Path source = this.getPath();
            Path target = Path.of(source.getParent() + "/backups", source.getFileName() + ".backup535");
            FileUtil.createFileIfNotExists(target);

            try {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (IOException exception) {
                this.plugin.error("Could not backup crate file: " + source);
                exception.printStackTrace();
            }
        }

        if (config.contains("Item")) {
            ItemStack itemStack = config.getCosmeticItem("Item").getItemStack();
            AdaptedItem provider = ItemHelper.vanilla(itemStack);
            config.set("ItemProvider", provider);
            config.remove("Item");
        }
        if (!config.contains("Preview")) {
            String oldId = config.getString("Preview_Config");
            config.set("Preview.Enabled", oldId != null);
            config.set("Preview.Id", oldId == null ? Placeholders.DEFAULT : oldId);
            config.remove("Preview_Config");
        }
        if (!config.contains("Animation")) {
            String oldId = config.getString("Animation_Config");
            config.set("Animation.Enabled", oldId != null);
            config.set("Animation.Id", oldId == null ? Placeholders.DEFAULT : oldId);
            config.remove("Animation_Config");
        }
        if (config.contains("Opening.Cooldown")) {
            int old = config.getInt("Opening.Cooldown");
            config.set("OpeningCooldown.Enabled", old != 0);
            config.set("OpeningCooldown.Value", this.openingCooldownTime);
            config.remove("Opening");
        }

        this.setName(config.getString("Name", this.getId()));
        this.setDescription(config.getStringList("Description"));

        this.setItem(ItemHelper.read(config, "ItemProvider").orElse(ItemHelper.vanilla(CrateUtils.getDefaultItem(this))));
        this.setItemStackable(config.getBoolean("ItemStackable", true));

        this.setPreviewEnabled(config.getBoolean("Preview.Enabled"));
        this.setPreviewId(config.getString("Preview.Id", Placeholders.DEFAULT));
        this.setOpeningEnabled(config.getBoolean("Animation.Enabled"));
        this.setOpeningId(config.getString("Animation.Id", Placeholders.DEFAULT));

        this.setOpeningCooldownEnabled(config.getBoolean("OpeningCooldown.Enabled"));
        this.setOpeningCooldownTime(config.getInt("OpeningCooldown.Value"));
        this.setOpeningLimitAmount(config.getInt("OpeningLimits.Amount"));

        this.setPermissionRequired(config.getBoolean("Permission_Required"));

        if (config.contains("Opening.Cost") || config.contains("Key.Ids")) {
            boolean keyRequired = config.getBoolean("Key.Required");
            Set<String> oldKeys = config.getStringSet("Key.Ids");

            if (!oldKeys.isEmpty() && CratesRegistries.getCostType(CostTypeId.KEY) instanceof KeyCostType keyType) {
                oldKeys.forEach(keyId -> {
                    KeyCostEntry entry = keyType.createEmpty();
                    entry.setKeyId(keyId);
                    entry.setAmount(1);

                    Cost cost = new Cost("key_" + keyId, keyRequired, keyId + " Key", ItemHelper.vanilla(new ItemStack(Material.TRIAL_KEY)), Collections.singletonList(entry));
                    config.set("CostOptions." + cost.getId(), cost);
                });
            }

            if (CratesRegistries.getCostType(CostTypeId.CURRENCY) instanceof EcoCostType ecoType) {
                for (String curId : config.getSection("Opening.Cost")) {
                    Currency currency = EconomyBridge.getCurrency(curId);
                    if (currency == null) continue;

                    double amount = config.getDouble("Opening.Cost." + curId);

                    EcoCostEntry entry = ecoType.createEmpty();
                    entry.setCurrencyId(curId);
                    entry.setAmount(amount);

                    Cost cost = new Cost("eco_" + curId, keyRequired, curId + " Currency", ItemHelper.adapt(currency.getIcon()), Collections.singletonList(entry));
                    config.set("CostOptions." + cost.getId(), cost);
                }
            }

            config.remove("Opening.Cost");
            config.remove("Key");
        }

        config.getSection("CostOptions").forEach(sId -> {
            Cost cost = Cost.read(config, "CostOptions." + sId, sId);
            this.addCost(cost);
        });

        this.blockPositions.addAll(config.getStringList("Block.Positions").stream().map(WorldPos::deserialize).toList());
        if (!Config.isCrateInAirBlocksAllowed()) {
            FoliaScheduler scheduler = FoliaScheduler.get();
            scheduler.runGlobal(() -> {
                List<WorldPos> blockPositionsTemp = new ArrayList<>(blockPositions);
                for (WorldPos pos : blockPositionsTemp) {
                    Location loc = pos.toLocation();
                    if (loc == null) continue;
                    scheduler.runRegion(loc, () -> {
                        Block block = pos.toBlock();
                        if (block == null || block.isEmpty()) {
                            this.blockPositions.remove(pos);
                        }
                    });
                }
            });
        }

        this.setPushbackEnabled(config.getBoolean("Block.Pushback.Enabled"));
        this.setHologramEnabled(config.getBoolean("Block.Hologram.Enabled"));
        this.setHologramTemplateId(config.getString("Block.Hologram.Template", Placeholders.DEFAULT));
        this.setHologramYOffset(config.getDouble("Block.Hologram.Y_Offset", 0D));

        this.setEffectType(config.getString("Block.Effect.Model", EffectId.NONE));
        this.setEffectParticle(UniParticle.read(config, "Block.Effect.Particle"));
        this.setEffectEnabled(config.getBoolean("Block.Effect.Enabled", !this.effectType.equalsIgnoreCase(EffectId.NONE)));

        this.setPostOpenCommands(config.getStringList("Post-Open.Commands"));

        for (String sId : config.getSection("Rewards.List")) {
            Reward reward = RewardFactory.read(this.plugin, this, sId, config, "Rewards.List." + sId);
            this.rewardMap.put(sId, reward);
        }

        // Load milestones only if the feature is enabled.
        if (Config.isMilestonesEnabled()) {
            this.setMilestonesRepeatable(config.getBoolean("Milestones.Repeatable"));
            for (String sId : config.getSection("Milestones.List")) {
                this.milestones.add(Milestone.read(this, config, "Milestones.List." + sId));
            }
        }
    }

    public void saveForce() {
        this.markDirty();
        this.saveIfDirty();
    }

    public void saveIfDirty() {
        if (this.dirty) {
            this.loadConfig().edit(this::write);
            this.dirty = false;
        }
    }

    private void write(@NotNull FileConfig config) {
        this.writeSettings(config);
        this.writeRewards(config);
        this.writeMilestones(config);
    }

    private void writeSettings(@NotNull FileConfig config) {
        config.set("Name", this.name);
        config.set("Description", this.description);
        config.set("ItemProvider", this.item);
        config.set("ItemStackable", this.itemStackable);
        config.set("Permission_Required", this.permissionRequired);

        config.set("Preview.Enabled", this.previewEnabled);
        config.set("Preview.Id", this.previewId);
        config.set("Animation.Enabled", this.openingEnabled);
        config.set("Animation.Id", this.openingId);

        config.set("OpeningCooldown.Enabled", this.openingCooldownEnabled);
        config.set("OpeningCooldown.Value", this.openingCooldownTime);
        config.set("OpeningLimits.Amount", this.openingLimitAmount);

        config.remove("CostOptions");
        this.getCosts().forEach(cost -> config.set("CostOptions." + cost.getId(), cost));

        config.set("Block.Positions", this.blockPositions.stream().map(WorldPos::serialize).toList());
        config.set("Block.Pushback.Enabled", this.pushbackEnabled);
        config.set("Block.Hologram.Enabled", this.hologramEnabled);
        config.set("Block.Hologram.Template", this.hologramTemplateId);
        config.set("Block.Hologram.Y_Offset", this.hologramYOffset);
        config.set("Block.Effect.Enabled", this.effectEnabled);
        config.set("Block.Effect.Model", this.effectType);
        config.remove("Block.Effect.Particle");
        this.effectParticle.write(config, "Block.Effect.Particle");

        config.set("Post-Open.Commands", this.postOpenCommands);
    }

    private void writeRewards(@NotNull FileConfig config) {
        config.remove("Rewards.List");
        this.getRewards().forEach(reward -> this.writeReward(config, reward));
    }

    private void writeReward(@NotNull FileConfig config, @NotNull Reward reward) {
        reward.write(config, "Rewards.List." + reward.getId());
    }

    private void writeMilestones(@NotNull FileConfig config) {
        // Write milestones only if the feature is enabled.
        if (!Config.isMilestonesEnabled()) return;

        config.set("Milestones.Repeatable", this.milestonesRepeatable);
        config.remove("Milestones.List");
        int i = 0;
        for (Milestone milestone : this.milestones) {
            milestone.write(config, "Milestones.List." + (i++));
        }
    }

    @NotNull
    public UnaryOperator<String> replacePlaceholders() {
        return Placeholders.CRATE.replacer(this);
    }

    public boolean hasProblems() {
        return !this.collectProblems().isEmpty();
    }

    @NotNull
    public ProblemReporter collectProblems() {
        ProblemCollector collector = new ProblemCollector(this.getName(), this.filePath.toString());

        if (!this.item.isValid()) collector.report(Lang.INSPECTIONS_GENERIC_ITEM.get(false));
        if (this.isPreviewEnabled() && !this.isPreviewValid()) collector.report(Lang.INSPECTIONS_CRATE_PREVIEW.get(false));
        if (this.isOpeningEnabled() && !this.isOpeningValid()) collector.report(Lang.INSPECTIONS_CRATE_OPENING.get(false));
        if (this.isHologramEnabled() && !this.isHologramTemplateValid()) collector.report(Lang.INSPECTIONS_CRATE_HOLOGRAM.get(false));

        this.postOpenCommands.stream().filter(Predicate.not(CrateUtils::isValidCommand)).forEach(command -> {
            collector.report("Post-Open Command '" + command + "' does no exist.");
        });

        this.costMap.values().forEach(cost -> {
            ProblemReporter reporter = cost.collectProblems();
            if (reporter.isEmpty()) return;

            collector.children("Problems in '" + cost.getId() + "' cost option.", reporter);
        });

        this.rewardMap.values().forEach(reward -> {
            ProblemReporter reporter = reward.collectProblems();
            if (reporter.isEmpty()) return;

            collector.children("Problems in '" + reward.getId() + "' reward.", reporter);
        });

        return collector;
    }

    @Nullable
    public GlobalCrateData getData() {
        return this.plugin.getDataManager().getCrateData(this.getId());
    }

    @NotNull
    public Optional<CachedProfile> getLastOpener() {
        GlobalCrateData data = this.getData();
        if (data == null) return Optional.empty();

        UUID playerId = data.getLatestOpenerId();
        String playerName = data.getLatestOpenerName();
        if (playerId == null || playerName == null) return Optional.empty();

        return Optional.of(PlayerProfiles.createProfile(playerId, playerName));
    }

    @Nullable
    public String getLatestReward() {
        GlobalCrateData data = this.getData();
        if (data == null || data.getLatestRewardId() == null) return null;

        Reward reward = this.getReward(data.getLatestRewardId());
        return reward == null ? null : reward.getName();
    }

    @Nullable
    public String getLastRewardName() {
        String last = this.getLatestReward();
        return last == null ? Lang.OTHER_LAST_REWARD_EMPTY.text() : last;
    }

    public void createHologram() {
        this.manageHologram(handler -> handler.render(this));
    }

    public void removeHologram() {
        this.manageHologram(handler -> handler.discard(this));
    }

    public void recreateHologram() {
        this.manageHologram(handler -> {
            handler.discard(this);
            handler.render(this);
        });
    }

    private void manageHologram(@NotNull Consumer<HologramManager> consumer) {
        if (this.hologramEnabled) {
            this.plugin.getHologramManager().ifPresent(consumer);
        }
    }

    public boolean hasRewards() {
        return !this.rewardMap.isEmpty();
    }

    public boolean hasMilestones() {
        return Config.isMilestonesEnabled() && !this.milestones.isEmpty();
    }

    public boolean isPreviewValid() {
        return this.plugin.getCrateManager().getPreviewById(this.previewId) != null;
    }

    public boolean isOpeningValid() {
        return this.plugin.getOpeningManager().getProviderById(this.openingId) != null;
    }

    public boolean isHologramTemplateValid() {
        return Config.getHologramTemplate(this.hologramTemplateId) != null;
    }

    @NotNull
    public CrateEffect getEffect() {
        return CratesRegistries.effectOrDummy(this.effectType);
    }

    public boolean hasPermission(@NotNull Player player) {
        if (!this.isPermissionRequired()) return true;

        return player.hasPermission(this.getPermission());
    }

    public boolean hasCooldownBypassPermission(@NotNull Player player) {
        return player.hasPermission(Perms.BYPASS_CRATE_COOLDOWN);
    }

    @NotNull
    public String getPermission() {
        return Perms.PREFIX_CRATE + this.getId();
    }

    @NotNull
    public List<String> getHologramText() {
        HologramTemplate template = Config.getHologramTemplate(this.hologramTemplateId);
        return template == null ? Collections.emptyList() : template.getText();
    }

    public boolean hasRewards(@NotNull Player player) {
        return this.hasRewards(player, null);
    }

    public boolean hasRewards(@NotNull Rarity rarity) {
        return this.hasRewards(null, rarity);
    }

    public boolean hasRewards(@Nullable Player player, @Nullable Rarity rarity) {
        return !this.getRewards(player, rarity).isEmpty();
    }

    @NotNull
    public Reward rollReward() {
        return this.rollReward(null, null);
    }

    @NotNull
    public Reward rollReward(@NotNull Rarity rarity) {
        return this.rollReward(null, rarity);
    }

    @NotNull
    public Reward rollReward(@NotNull Player player) {
        return this.rollReward(player, null);
    }

    @NotNull
    public Reward rollReward(@Nullable Player player, @Nullable Rarity rarity) {
        List<Reward> rewards = this.getRewards(player, rarity);

        // If no rarity is specified, we have to select a random one and filter rewards by selected rarity.
        // Otherwise reward list is already obtained with specified rarity.
        if (rarity == null) {
            // Build rarity weight map without streams
            Map<Rarity, Double> rarityWeights = new HashMap<>();
            for (Reward reward : rewards) {
                Rarity r = reward.getRarity();
                if (!rarityWeights.containsKey(r)) {
                    rarityWeights.put(r, r.getWeight());
                }
            }

            Rarity rarityRoll = Rnd.getByWeight(rarityWeights);
            // Filter in-place
            rewards.removeIf(reward -> reward.getRarity() != rarityRoll);
        }

        return rollRewardDirect(rewards);
    }

    @NotNull
    private Reward rollRewardDirect(@NotNull List<Reward> rewards) {
        Map<Reward, Double> weightMap = new HashMap<>();
        for (Reward reward : rewards) {
            weightMap.put(reward, reward.getWeight());
        }
        return Rnd.getByWeight(weightMap);
    }

    public void addBlockPosition(@NotNull Location location) {
        WorldPos pos = WorldPos.from(location);

        this.plugin.getCrateManager().removeCratePositions(this);
        this.blockPositions.add(pos);
        this.plugin.getCrateManager().addCratePositions(this);
    }

    public void clearBlockPositions() {
        this.plugin.getCrateManager().removeCratePositions(this);
        this.blockPositions.clear();
    }

    public int countRewards() {
        return this.rewardMap.size();
    }

    public int countMilestones() {
        return this.milestones.size();
    }

    public int countMaxOpenings(@NotNull Player player) {
        int max = -1;
        for (Cost cost : this.costMap.values()) {
            if (!cost.isEnabled()) continue;
            int count = cost.countMaxOpenings(player);
            if (count > max) max = count;
        }
        return max;
    }

    public void markDirty() {
        this.dirty = true;
    }

    public boolean hasFile() {
        return Files.exists(this.filePath);
    }

    @NotNull
    public String getId() {
        return this.id;
    }

    @Override
    @NotNull
    public Path getPath() {
        return this.filePath;
    }

    @NotNull
    public String getName() {
        return this.name;
    }

    public void setName(@NotNull String name) {
        this.name = name;
    }

    @NotNull
    public List<String> getDescription() {
        return this.description;
    }

    public void setDescription(@NotNull List<String> description) {
        this.description = description;
    }

    @NotNull
    public AdaptedItem getItem() {
        return this.item;
    }

    public void setItem(@NotNull AdaptedItem item) {
        this.item = item;
    }

    @NotNull
    public ItemStack getRawItemStack() {
        return this.getItemStack(false);
    }

    @NotNull
    public ItemStack getItemStack() {
        return this.getItemStack(true);
    }

    @NotNull
    public ItemStack getItemStack(boolean fullData) {
        ItemStack itemStack = this.item.itemStack().orElse(CrateUtils.getDefaultItem(this));

        ItemUtil.editMeta(itemStack, meta -> {
            //ItemUtil.setCustomName(meta, this.name);
            //ItemUtil.setLore(meta, this.description);

            if (fullData) {
                meta.setMaxStackSize(this.itemStackable ? null : 1);
                PDCUtil.set(meta, Keys.crateId, this.getId());
            }
        });

        return itemStack;
    }

    public boolean isItemStackable() {
        return this.itemStackable;
    }

    public void setItemStackable(boolean itemStackable) {
        this.itemStackable = itemStackable;
    }

    @NotNull
    public String getPreviewId() {
        return this.previewId;
    }

    public void setPreviewId(@NotNull String previewId) {
        this.previewId = previewId.toLowerCase();
    }

    public boolean isPreviewEnabled() {
        return this.previewEnabled;
    }

    public void setPreviewEnabled(boolean previewEnabled) {
        this.previewEnabled = previewEnabled;
    }

    @NotNull
    public String getOpeningId() {
        return this.openingId;
    }

    public void setOpeningId(@NotNull String openingId) {
        this.openingId = openingId.toLowerCase();
    }

    public boolean isOpeningEnabled() {
        return this.openingEnabled;
    }

    public void setOpeningEnabled(boolean openingEnabled) {
        this.openingEnabled = openingEnabled;
    }



    public boolean isPermissionRequired() {
        return permissionRequired;
    }

    public void setPermissionRequired(boolean isPermissionRequired) {
        this.permissionRequired = isPermissionRequired;
    }

    public boolean isOpeningCooldownEnabled() {
        return this.openingCooldownEnabled;
    }

    public void setOpeningCooldownEnabled(boolean openingCooldownEnabled) {
        this.openingCooldownEnabled = openingCooldownEnabled;
    }

    public int getOpeningCooldownTime() {
        return this.openingCooldownTime;
    }

    public void setOpeningCooldownTime(int openingCooldownTime) {
        this.openingCooldownTime = openingCooldownTime;
    }

    public int getOpeningLimitAmount() {
        return this.openingLimitAmount;
    }

    public void setOpeningLimitAmount(int openingLimitAmount) {
        this.openingLimitAmount = Math.max(1, openingLimitAmount);
    }

    @NotNull
    public Map<String, Cost> getCostMap() {
        return this.costMap;
    }

    @NotNull
    public List<Cost> getCosts() {
        return List.copyOf(this.costMap.values());
    }

    /**
     * Internal iteration helper to avoid creating defensive copies.
     */
    public void forEachCost(@NotNull Consumer<Cost> action) {
        for (Cost cost : this.costMap.values()) {
            action.accept(cost);
        }
    }

    public void addCost(@NotNull Cost cost) {
        this.costMap.put(cost.getId(), cost);
    }

    public void removeCost(@NotNull Cost cost) {
        this.costMap.remove(cost.getId());
    }

    public boolean hasCost(@NotNull String id) {
        return this.costMap.containsKey(id);
    }

    @Nullable
    public Cost getCost(@NotNull String id) {
        return this.costMap.get(id);
    }

    @NotNull
    public Optional<Cost> getFirstCost() {
        for (Cost cost : this.costMap.values()) {
            if (cost.isAvailable()) return Optional.of(cost);
        }
        return Optional.empty();
    }

    @NotNull
    public Optional<Cost> getAnyCost(@NotNull Player player) {
        Cost fallback = null;
        for (Cost cost : this.costMap.values()) {
            if (!cost.isAvailable()) continue;
            if (cost.canAfford(player)) return Optional.of(cost);
            if (fallback == null) fallback = cost;
        }
        return Optional.ofNullable(fallback);
    }

    public boolean hasCost() {
        if (this.costMap.isEmpty()) return false;
        for (Cost cost : this.costMap.values()) {
            if (cost.isAvailable()) return true;
        }
        return false;
    }

    public boolean hasMultipleCosts() {
        int count = 0;
        for (Cost cost : this.costMap.values()) {
            if (cost.isAvailable()) {
                count++;
                if (count >= 2) return true;
            }
        }
        return false;
    }

    public boolean isPushbackEnabled() {
        return this.pushbackEnabled;
    }

    public void setPushbackEnabled(boolean blockPushback) {
        this.pushbackEnabled = blockPushback;
    }

    @NotNull
    public Set<WorldPos> getBlockPositions() {
        return Set.copyOf(this.blockPositions);
    }

    /**
     * Internal iteration helper to avoid creating defensive copies.
     */
    public void forEachBlockPosition(@NotNull Consumer<WorldPos> action) {
        for (WorldPos pos : this.blockPositions) {
            action.accept(pos);
        }
    }

    public boolean isHologramEnabled() {
        return this.hologramEnabled;
    }

    public void setHologramEnabled(boolean hologramEnabled) {
        this.hologramEnabled = hologramEnabled;
    }

    @NotNull
    public String getHologramTemplateId() {
        return this.hologramTemplateId;
    }

    public void setHologramTemplateId(@NotNull String hologramTemplateId) {
        this.hologramTemplateId = hologramTemplateId.toLowerCase();
    }

    public double getHologramYOffset() {
        return hologramYOffset;
    }

    public void setHologramYOffset(double hologramYOffset) {
        this.hologramYOffset = hologramYOffset;
    }

    public boolean isEffectEnabled() {
        return this.effectEnabled;
    }

    public void setEffectEnabled(boolean effectEnabled) {
        this.effectEnabled = effectEnabled;
    }

    @NotNull
    public String getEffectType() {
        return this.effectType;
    }

    public void setEffectType(@NotNull String effectType) {
        this.effectType = effectType;
    }

    @NotNull
    public UniParticle getEffectParticle() {
        return this.effectParticle;
    }

    public void setEffectParticle(@NotNull UniParticle wrapped) {
        if (!CrateUtils.isSupportedParticle(wrapped.getParticle()) || wrapped.getParticle() == null) {
            wrapped = UniParticle.of(Particle.CLOUD);
        }

        this.effectParticle = wrapped;
        this.effectParticle.validateData();
    }

    @NotNull
    public List<String> getPostOpenCommands() {
        return this.postOpenCommands;
    }

    public void setPostOpenCommands(@Nullable List<String> postOpenCommands) {
        this.postOpenCommands = postOpenCommands == null ? new ArrayList<>() : new ArrayList<>(postOpenCommands);
    }

    @NotNull
    public LinkedHashMap<String, Reward> getRewardsMap() {
        return this.rewardMap;
    }

    @NotNull
    public Set<Rarity> getRarities() {
        Set<Rarity> rarities = new HashSet<>();
        for (Reward reward : this.rewardMap.values()) {
            rarities.add(reward.getRarity());
        }
        return rarities;
    }

    @NotNull
    public Set<String> getRewardIds() {
        return new LinkedHashSet<>(this.rewardMap.keySet());
    }

    @NotNull
    public Set<Reward> getRewards() {
        return Set.copyOf(this.rewardMap.values());
    }

    /**
     * Internal iteration helper to avoid creating defensive copies.
     */
    public void forEachReward(@NotNull Consumer<Reward> action) {
        for (Reward reward : this.rewardMap.values()) {
            action.accept(reward);
        }
    }

    @NotNull
    public List<Reward> getRewards(@NotNull Rarity rarity) {
        return this.getRewards(null, rarity);
    }

    @NotNull
    public List<Reward> getRewards(@NotNull Player player) {
        return this.getRewards(player, null);
    }

    @NotNull
    public List<Reward> getRewards(@Nullable Player player, @Nullable Rarity rarity) {
        List<Reward> result = new ArrayList<>();
        for (Reward reward : this.rewardMap.values()) {
            if (rarity != null && reward.getRarity() != rarity) continue;
            if (player != null && !reward.canWin(player)) continue;
            result.add(reward);
        }
        return result;
    }

    public void setRewards(@NotNull List<Reward> rewards) {
        this.rewardMap.clear();
        this.rewardMap.putAll(rewards.stream().collect(
            Collectors.toMap(Reward::getId, Function.identity(), (has, add) -> add, LinkedHashMap::new)));
    }

    @Nullable
    public Reward getReward(@NotNull String id) {
        return this.rewardMap.get(id.toLowerCase());
    }

    @Nullable
    public Reward getMilestoneReward(int openings) {
        Milestone milestone = this.getMilestone(openings);
        return milestone == null ? null : milestone.getReward();
    }

    public void addReward(@NotNull Reward reward) {
        this.rewardMap.put(reward.getId(), reward);
    }

    public void removeReward(@NotNull Reward reward) {
        this.removeReward(reward.getId());
        this.plugin.getDataManager().handleRewardRemoval(reward);
    }

    public void removeReward(@NotNull String id) {
        this.rewardMap.remove(id);
    }

    @NotNull
    public Set<Milestone> getMilestones() {
        return this.milestones;
    }

    @Nullable
    public Milestone getMilestone(int openings) {
        for (Milestone milestone : this.milestones) {
            if (milestone.getOpenings() == openings) return milestone;
        }
        return null;
    }

    public boolean isMilestonesRepeatable() {
        return milestonesRepeatable;
    }

    public void setMilestonesRepeatable(boolean milestonesRepeatable) {
        this.milestonesRepeatable = milestonesRepeatable;
    }

    public int getMaxMilestone() {
        int max = 0;
        for (Milestone milestone : this.milestones) {
            if (milestone.getOpenings() > max) max = milestone.getOpenings();
        }
        return max;
    }

    @Nullable
    public Milestone getNextMilestone(int openings) {
        Milestone closest = null;
        for (Milestone milestone : this.milestones) {
            if (milestone.getOpenings() > openings) {
                if (closest == null || milestone.getOpenings() < closest.getOpenings()) {
                    closest = milestone;
                }
            }
        }
        return closest;
    }
}
