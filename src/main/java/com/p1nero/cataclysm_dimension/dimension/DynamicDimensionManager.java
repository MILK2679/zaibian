package com.p1nero.cataclysm_dimension.dimension;

import com.google.common.collect.ImmutableList;
import com.p1nero.cataclysm_dimension.CataclysmDimensionMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkProgressListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.WorldData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.LevelEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时创建/卸载 ServerLevel 实例的管理器。
 * <p>
 * 每次有玩家用"眼睛"进维度时，会以原始 LevelStem 为模板 new 一个带唯一 ResourceKey 的
 * ServerLevel 并塞进 server.levels，Minecraft 会把它的存档写到:
 * <pre>  world/dimensions/cataclysm_dimension/&lt;原维度名&gt;_&lt;玩家UUID短&gt;_&lt;hex随机&gt;/</pre>
 * 与 LiteDungeon 的 <code>LiteDungeon_3_xxxx</code> 思路完全一致，玩家互不干扰。
 */
public class DynamicDimensionManager {

    /** 玩家UUID → (模板维度Key → 当前实例Key)。用于"保留实例"模式下复用之前的实例。 */
    public static final Map<UUID, Map<ResourceKey<Level>, ResourceKey<Level>>> PLAYER_INSTANCES = new ConcurrentHashMap<>();

    /** 所有活着的动态实例。用于 tick 扫描清理。 */
    public static final Set<ResourceKey<Level>> ACTIVE_INSTANCES = ConcurrentHashMap.newKeySet();

    /** 实例 → 空人后剩余的 tick。为 0 时触发卸载+删文件夹。 */
    public static final Map<ResourceKey<Level>, Integer> EMPTY_COUNTDOWN = new ConcurrentHashMap<>();

    /** 反查：实例Key → 模板Key。清理时定位该实例对应的原维度。 */
    public static final Map<ResourceKey<Level>, ResourceKey<Level>> INSTANCE_TO_TEMPLATE = new ConcurrentHashMap<>();

    /**
     * 判断一个维度 Key 是否为本 mod 动态创建出来的实例。
     */
    public static boolean isDynamicInstance(ResourceKey<Level> key) {
        return ACTIVE_INSTANCES.contains(key);
    }

    /**
     * 取得 / 创建该玩家对某个模板维度的实例。
     *
     * @param alwaysFresh true = 每次都新建一个崭新实例（把旧的留给 tick 扫描清理）；
     *                    false = 如果玩家之前的实例还活着就复用。
     */
    public static ResourceKey<Level> getOrCreateInstance(MinecraftServer server,
                                                         ResourceKey<Level> templateKey,
                                                         UUID playerId,
                                                         boolean alwaysFresh) {
        Map<ResourceKey<Level>, ResourceKey<Level>> playerMap =
                PLAYER_INSTANCES.computeIfAbsent(playerId, k -> new HashMap<>());

        if (!alwaysFresh) {
            ResourceKey<Level> existing = playerMap.get(templateKey);
            if (existing != null && server.getLevel(existing) != null) {
                // 续用，并取消排队中的清理
                EMPTY_COUNTDOWN.remove(existing);
                return existing;
            }
        }

        ResourceKey<Level> instanceKey = buildInstanceKey(templateKey, playerId, server);
        ServerLevel level = createDynamicLevel(server, templateKey, instanceKey);
        if (level == null) {
            // 回退：模板都找不到就还是用模板本身，避免卡死
            return templateKey;
        }
        playerMap.put(templateKey, instanceKey);
        ACTIVE_INSTANCES.add(instanceKey);
        INSTANCE_TO_TEMPLATE.put(instanceKey, templateKey);
        return instanceKey;
    }

    private static ResourceKey<Level> buildInstanceKey(ResourceKey<Level> templateKey, UUID playerId, MinecraftServer server) {
        String shortUuid = playerId.toString().replace("-", "").substring(0, 8);
        long random = server.overworld().getRandom().nextLong() & 0xFFFFFFFFL;
        String path = templateKey.location().getPath() + "_" + shortUuid + "_" + Long.toHexString(random);
        return ResourceKey.create(Registries.DIMENSION,
                new ResourceLocation(CataclysmDimensionMod.MOD_ID, path));
    }

    /**
     * 以模板的 LevelStem 为蓝本 new 一个 ServerLevel，挂到 server.levels 上。
     * <p>
     * LevelStem 共享没有问题（里面的 ChunkGenerator、BiomeSource、DimensionType 都是只读数据，
     * 各实例的 Chunk/实体/存档状态都由各自的 ServerLevel 自己持有）。
     */
    @Nullable
    private static ServerLevel createDynamicLevel(MinecraftServer server,
                                                  ResourceKey<Level> templateKey,
                                                  ResourceKey<Level> instanceKey) {
        // 1) 拿模板 LevelStem
        ResourceKey<LevelStem> stemKey = ResourceKey.create(Registries.LEVEL_STEM, templateKey.location());
        LevelStem stem = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).get(stemKey);
        if (stem == null) {
            CataclysmDimensionMod.LOGGER.error("[Cataclysm Dimension]: 找不到模板维度的 LevelStem: {}，动态实例创建失败。", templateKey.location());
            return null;
        }

        ServerLevel overworld = server.overworld();
        WorldData worldData = server.getWorldData();
        DerivedLevelData derivedLevelData = new DerivedLevelData(worldData, worldData.overworldData());

        // 2) 进度监听器 — 11 是 vanilla 创建世界时用的默认半径
        ChunkProgressListener progressListener = server.progressListenerFactory.create(11);

        // 3) 每个实例用不同的 biomeZoomSeed，避免"看起来完全一样"
        long seed = worldData.worldGenOptions().seed() ^ instanceKey.location().hashCode();

        ServerLevel newLevel;
        try {
            newLevel = new ServerLevel(
                    server,
                    server.executor,
                    server.storageSource,
                    derivedLevelData,
                    instanceKey,
                    stem,
                    progressListener,
                    false,
                    BiomeManager.obfuscateSeed(seed),
                    ImmutableList.of(),
                    false,
                    overworld.getRandomSequences()
            );
        } catch (Throwable t) {
            CataclysmDimensionMod.LOGGER.error("[Cataclysm Dimension]: 构造 ServerLevel 失败: {}", instanceKey.location(), t);
            return null;
        }

        // 4) 世界边界跟主世界同步
        overworld.getWorldBorder().addListener(
                new BorderChangeListener.DelegateBorderChangeListener(newLevel.getWorldBorder()));

        // 5) 挂到 server.levels
        server.levels.put(instanceKey, newLevel);

        // 6) 广播给其它 mod (如 JEI、一些传送 mod 等) 知道世界加载了
        MinecraftForge.EVENT_BUS.post(new LevelEvent.Load(newLevel));

        CataclysmDimensionMod.LOGGER.info("[Cataclysm Dimension]: 动态维度实例已创建 -> {} (模板 {})",
                instanceKey.location(), templateKey.location());
        return newLevel;
    }

    /**
     * 每 server tick 调用：没有玩家的实例会被倒计时 → 归零后卸载并删除对应文件夹。
     */
    public static void tickCleanup(MinecraftServer server, int delayTicksAfterEmpty) {
        if (ACTIVE_INSTANCES.isEmpty()) return;

        Set<ResourceKey<Level>> toRemove = new HashSet<>();
        for (ResourceKey<Level> instanceKey : ACTIVE_INSTANCES) {
            ServerLevel level = server.getLevel(instanceKey);
            if (level == null) {
                toRemove.add(instanceKey);
                continue;
            }
            if (level.players().isEmpty()) {
                int remain = EMPTY_COUNTDOWN.getOrDefault(instanceKey, delayTicksAfterEmpty);
                remain--;
                if (remain <= 0) {
                    if (unloadAndDelete(server, instanceKey)) {
                        toRemove.add(instanceKey);
                    }
                } else {
                    EMPTY_COUNTDOWN.put(instanceKey, remain);
                }
            } else {
                // 有人回来了，取消倒计时
                EMPTY_COUNTDOWN.remove(instanceKey);
            }
        }
        for (ResourceKey<Level> k : toRemove) {
            ACTIVE_INSTANCES.remove(k);
            EMPTY_COUNTDOWN.remove(k);
            INSTANCE_TO_TEMPLATE.remove(k);
            // 把 PLAYER_INSTANCES 里指向该 key 的记录也清掉
            for (Map<ResourceKey<Level>, ResourceKey<Level>> pm : PLAYER_INSTANCES.values()) {
                pm.values().removeIf(v -> v.equals(k));
            }
        }
    }

    /**
     * 卸载一个实例并删除对应文件夹。成功返回 true。
     */
    public static boolean unloadAndDelete(MinecraftServer server, ResourceKey<Level> instanceKey) {
        ServerLevel level = server.getLevel(instanceKey);
        if (level == null) {
            // 已经不在了，只需删文件夹
            deleteInstanceFolder(server, instanceKey);
            server.levels.remove(instanceKey);
            return true;
        }
        if (!level.players().isEmpty()) {
            // 不应该删除有玩家的维度
            return false;
        }

        try {
            // 丢弃剩余实体，防止被保存进文件
            level.getAllEntities().forEach(e -> {
                if (!(e instanceof net.minecraft.world.entity.player.Player)) {
                    e.discard();
                }
            });

            // 先广播卸载事件
            MinecraftForge.EVENT_BUS.post(new LevelEvent.Unload(level));

            // 关掉 ServerLevel（内部会关闭 ChunkSource + IOWorker，并 flush）
            level.close();
        } catch (IOException e) {
            CataclysmDimensionMod.LOGGER.error("[Cataclysm Dimension]: 关闭 ServerLevel 失败 {}", instanceKey.location(), e);
        } catch (Throwable t) {
            CataclysmDimensionMod.LOGGER.error("[Cataclysm Dimension]: 卸载实例异常 {}", instanceKey.location(), t);
        }

        // 从 server.levels 摘除
        server.levels.remove(instanceKey);

        // 删文件夹
        deleteInstanceFolder(server, instanceKey);

        CataclysmDimensionMod.LOGGER.info("[Cataclysm Dimension]: 动态维度实例已卸载并删除 -> {}", instanceKey.location());
        return true;
    }

    private static void deleteInstanceFolder(MinecraftServer server, ResourceKey<Level> instanceKey) {
        Path dimFolder = server.storageSource.getDimensionPath(instanceKey);
        try {
            if (Files.exists(dimFolder)) {
                deleteRecursively(dimFolder);
            }
        } catch (IOException e) {
            CataclysmDimensionMod.LOGGER.error("[Cataclysm Dimension]: 删除实例文件夹失败 {}", dimFolder, e);
        }
    }

    private static void deleteRecursively(@NotNull Path path) throws IOException {
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public @NotNull FileVisitResult postVisitDirectory(@NotNull Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 服务器关闭时一次性清掉所有实例目录（它们本来就是临时的，没必要保留）。
     */
    public static void onServerStopping(MinecraftServer server) {
        for (ResourceKey<Level> key : new HashSet<>(ACTIVE_INSTANCES)) {
            unloadAndDelete(server, key);
        }
        PLAYER_INSTANCES.clear();
        ACTIVE_INSTANCES.clear();
        EMPTY_COUNTDOWN.clear();
        INSTANCE_TO_TEMPLATE.clear();
    }
}
