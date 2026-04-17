package com.p1nero.cataclysm_dimension;

import com.github.L_Ender.cataclysm.init.ModItems;
import com.mojang.logging.LogUtils;
import com.p1nero.cataclysm_dimension.dimension.DynamicDimensionManager;
import com.p1nero.cataclysm_dimension.worldgen.CataclysmDimensions;
import com.p1nero.cataclysm_dimension.worldgen.placements.CDPlacementTypes;
import com.p1nero.cataclysm_dimension.worldgen.portal.CDNetherTeleporter;
import com.p1nero.cataclysm_dimension.worldgen.portal.CDTeleporter;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.ITeleporter;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Supplier;

@Mod(CataclysmDimensionMod.MOD_ID)
public class CataclysmDimensionMod {
    public static final String MOD_ID = "cataclysm_dimension";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CataclysmDimensionMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        MinecraftForge.EVENT_BUS.addListener(this::onItemUse);
        MinecraftForge.EVENT_BUS.addListener(this::onToolTip);
        MinecraftForge.EVENT_BUS.addListener(this::onServerLevelTick);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerChangeDim);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
        CataclysmDimensionModConfig.loadConfig();
        bus.addListener(EventPriority.HIGHEST, this::onDatapackLoad);
        CDPlacementTypes.STRUCTURE_PLACEMENT_TYPES.register(bus);
    }

    /**
     * 每次眼睛使用都新建 teleporter，避免 CDTeleporter.pos 在多次调用间被"上移/西移"污染。
     */
    public record DimensionTeleportInfo(ResourceKey<Level> dimensionKey, Supplier<ITeleporter> teleporterFactory) {
        public ITeleporter newTeleporter() {
            return teleporterFactory.get();
        }
    }

    public static Map<Item, DimensionTeleportInfo> TELEPORT_MAP;

    private void initMap() {
        TELEPORT_MAP = Map.of(
                ModItems.ABYSS_EYE.get(), new DimensionTeleportInfo(
                        CataclysmDimensions.CATACLYSM_ABYSSAL_DEPTHS_LEVEL_KEY,
                        () -> new CDTeleporter(new BlockPos(0, 200, 0))
                ),
                ModItems.MECH_EYE.get(), new DimensionTeleportInfo(
                        CataclysmDimensions.CATACLYSM_FORGE_OF_AEONS_LEVEL_KEY,
                        () -> new CDTeleporter(new BlockPos(0, 150, 0))
                ),
                ModItems.FLAME_EYE.get(), new DimensionTeleportInfo(
                        CataclysmDimensions.CATACLYSM_INFERNOS_MAW_LEVEL_KEY,
                        () -> new CDNetherTeleporter(new BlockPos(0, 64, 0))
                ),
                ModItems.VOID_EYE.get(), new DimensionTeleportInfo(
                        CataclysmDimensions.CATACLYSM_BASTION_LOST_LEVEL_KEY,
                        () -> new CDTeleporter(new BlockPos(0, 150, 0))
                ),
                ModItems.MONSTROUS_EYE.get(), new DimensionTeleportInfo(
                        CataclysmDimensions.CATACLYSM_SOULS_ANVIL_LEVEL_KEY,
                        () -> new CDNetherTeleporter(new BlockPos(0, 64, 0))
                ),
                ModItems.DESERT_EYE.get(), new DimensionTeleportInfo(
                        CataclysmDimensions.CATACLYSM_PHARAOHS_BANE_LEVEL_KEY,
                        () -> new CDTeleporter(new BlockPos(0, 200, 0), 400)
                ),
                ModItems.CURSED_EYE.get(), new DimensionTeleportInfo(
                        CataclysmDimensions.CATACLYSM_ETERNAL_FROSTHOLD_LEVEL_KEY,
                        () -> new CDTeleporter(new BlockPos(0, 200, 0), 400)
                ),
                ModItems.STORM_EYE.get(), new DimensionTeleportInfo(
                        CataclysmDimensions.CATACLYSM_SANCTUM_FALLEN_LEVEL_KEY,
                        () -> new CDTeleporter(new BlockPos(0, 200, 0))
                )
        );
    }

    private void onItemUse(LivingEntityUseItemEvent event) {
        if (!CataclysmDimensionModConfig.ENABLE_TELEPORT_EYE) {
            return;
        }
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide) {
            return;
        }
        if (!entity.isShiftKeyDown()) {
            return;
        }

        ItemStack itemStack = event.getItem();
        MinecraftServer server = entity.level().getServer();
        if (server == null) {
            return;
        }
        if (entity instanceof Player p && p.getCooldowns().isOnCooldown(itemStack.getItem())) {
            return;
        }
        if (TELEPORT_MAP == null) {
            initMap();
        }
        DimensionTeleportInfo info = TELEPORT_MAP.get(itemStack.getItem());
        if (info == null) {
            return;
        }

        // ---- 核心改动 ----
        // 如果开启"每个玩家独立实例"，给玩家开专属动态维度；否则退化到原版行为。
        ResourceKey<Level> targetKey = info.dimensionKey();
        if (CataclysmDimensionModConfig.PER_PLAYER_INSTANCE && entity instanceof ServerPlayer sp) {
            targetKey = DynamicDimensionManager.getOrCreateInstance(
                    server,
                    info.dimensionKey(),
                    sp.getUUID(),
                    CataclysmDimensionModConfig.FRESH_INSTANCE_EACH_TIME
            );
        }

        ServerLevel level = server.getLevel(targetKey);
        if (level == null) {
            LOGGER.error("[Cataclysm Dimension]: 目标维度加载失败 {}", targetKey.location());
            return;
        }

        entity.changeDimension(level, info.newTeleporter());
        if (entity instanceof ServerPlayer player) {
            player.getCooldowns().addCooldown(itemStack.getItem(), 600);
            player.connection.send(new ClientboundSoundPacket(
                    BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.PORTAL_TRAVEL),
                    SoundSource.PLAYERS, player.getX(), player.getY(), player.getZ(),
                    1.0F, 1.0F, player.getRandom().nextInt()));
        }
    }

    /**
     * 玩家重连时：若保存在已被删除的动态维度里，送回主世界出生点，防止掉虚空。
     */
    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ResourceKey<Level> dim = player.level().dimension();
        if (dim.location().getNamespace().equals(MOD_ID) && server.getLevel(dim) == null) {
            ServerLevel overworld = server.overworld();
            BlockPos spawn = overworld.getSharedSpawnPos();
            player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                    player.getYRot(), player.getXRot());
            player.displayClientMessage(Component.translatable("msg.cataclysm_dimension.instance_gone")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
    }

    /**
     * 玩家离开动态实例时，把自己的 PLAYER_INSTANCES 映射清理掉，
     * 避免下次复用时指向一个即将被卸载的实例。实际删文件夹交给 tickCleanup。
     */
    private void onPlayerChangeDim(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        ResourceKey<Level> from = event.getFrom();
        if (DynamicDimensionManager.isDynamicInstance(from)) {
            DynamicDimensionManager.PLAYER_INSTANCES
                    .getOrDefault(sp.getUUID(), Collections.emptyMap())
                    .values().removeIf(v -> v.equals(from));
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        DynamicDimensionManager.onServerStopping(event.getServer());
    }

    /**
     * 记录是否删过了(沿用原逻辑，仅为兼容老的"共享模板清 region"模式)
     */
    public static final Map<ResourceLocation, Boolean> RESOURCE_KEY_BOOLEAN_MAP = new HashMap<>();
    public static final Map<ResourceLocation, Integer> RESOURCE_LOCATION_INTEGER_MAP = new HashMap<>();
    public static final int DELAY = 200;

    /**
     * 每 tick：
     *  1) 启用"每玩家独立实例"时，DynamicDimensionManager 扫描空实例自动卸载+删文件夹
     *  2) 启用老的"无人清空模板维度"时，沿用原逻辑做兜底
     */
    private void onServerLevelTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();

        // --- 新：动态实例清理 ---
        if (CataclysmDimensionModConfig.PER_PLAYER_INSTANCE) {
            DynamicDimensionManager.tickCleanup(server, CataclysmDimensionModConfig.INSTANCE_CLEANUP_DELAY_TICKS);
        }

        // --- 老：共享模板维度的 region 清理（保留，兼容旧存档）---
        if (!CataclysmDimensionModConfig.RESET_DIMENSION_IF_NO_PLAYER) {
            return;
        }
        for (ResourceKey<Level> levelResourceKey : CataclysmDimensions.LEVELS) {
            ServerLevel serverLevel = server.getLevel(levelResourceKey);
            if (serverLevel != null) {
                ResourceLocation resourceLocation = levelResourceKey.location();
                if (!serverLevel.players().isEmpty()) {
                    RESOURCE_KEY_BOOLEAN_MAP.put(resourceLocation, false);
                } else {
                    if (!RESOURCE_KEY_BOOLEAN_MAP.getOrDefault(resourceLocation, false)) {
                        try {
                            LOGGER.info("[Cataclysm Dimension]: No player inside. trying to reset dimension {}.", resourceLocation);
                            IOWorker ioWorker = ((IOWorker) serverLevel.getChunkSource().chunkScanner());
                            serverLevel.noSave = false;
                            serverLevel.save(null, true, true);
                            ioWorker.storage.regionCache.clear();
                            RESOURCE_LOCATION_INTEGER_MAP.put(resourceLocation, DELAY);
                            LOGGER.info("[Cataclysm Dimension]: Dimension {} will be reset after {} second.", resourceLocation, DELAY / 20);
                            RESOURCE_KEY_BOOLEAN_MAP.put(resourceLocation, true);
                            List<Entity> newList = new ArrayList<>();
                            serverLevel.getAllEntities().forEach(newList::add);
                            for (Entity entity : newList) {
                                entity.discard();
                            }
                        } catch (Exception e) {
                            LOGGER.error("[Cataclysm Dimension]: Failed to reset dimension {}.", resourceLocation, e);
                            RESOURCE_KEY_BOOLEAN_MAP.put(resourceLocation, true);
                        }
                    }

                    int current = RESOURCE_LOCATION_INTEGER_MAP.getOrDefault(resourceLocation, 0);
                    if (current > 0) {
                        RESOURCE_LOCATION_INTEGER_MAP.put(resourceLocation, current - 1);
                        if (current == 1) {
                            IOWorker ioWorker = ((IOWorker) serverLevel.getChunkSource().chunkScanner());
                            try {
                                if (Files.exists(ioWorker.storage.folder)) {
                                    ioWorker.storage.regionCache.clear();
                                    deleteFile(ioWorker.storage.folder, resourceLocation);
                                    deleteFile(ioWorker.storage.folder.getParent().resolve("entities"), resourceLocation);
                                    deleteFile(ioWorker.storage.folder.getParent().resolve("poi"), resourceLocation);
                                } else {
                                    LOGGER.info("[Cataclysm Dimension]: No region files in {}. Skipped.", resourceLocation);
                                }
                            } catch (IOException e) {
                                LOGGER.error("[Cataclysm Dimension]: Failed to reset dimension {}.", resourceLocation, e);
                            }
                        }
                    }
                }
            }
        }
    }

    private void deleteFile(Path folder, ResourceLocation dimId) throws IOException {
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override
            public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) throws IOException {
                if (Files.deleteIfExists(file)) {
                    LOGGER.info("[Cataclysm Dimension]: {} cache Deleted -> {}", dimId, file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void onToolTip(ItemTooltipEvent event) {
        if (!CataclysmDimensionModConfig.ENABLE_TELEPORT_EYE) {
            return;
        }
        if (List.of(ModItems.ABYSS_EYE.get(), ModItems.STORM_EYE.get(), ModItems.CURSED_EYE.get(),
                ModItems.MECH_EYE.get(), ModItems.FLAME_EYE.get(), ModItems.DESERT_EYE.get(),
                ModItems.MONSTROUS_EYE.get(), ModItems.VOID_EYE.get()).contains(event.getItemStack().getItem())) {
            event.getToolTip().add(Component.translatable("tip.cataclysm_dimension.enter").withStyle(ChatFormatting.GRAY));
            if (CataclysmDimensionModConfig.PER_PLAYER_INSTANCE) {
                event.getToolTip().add(Component.translatable("tip.cataclysm_dimension.instance").withStyle(ChatFormatting.DARK_AQUA));
            }
        }
    }

    private void onDatapackLoad(AddPackFindersEvent event) {
        if (event.getPackType() == PackType.SERVER_DATA) {
            addNewDatapack(event, "base_dimension");
            addNewDatapack(event, CataclysmDimensionModConfig.KEEP_STRUCTURES_IN_ORIGINAL_DIMENSIONS ? "keep_original" : "not_keep_original");
            if (CataclysmDimensionModConfig.RANDOM_SPREAD_IN_DIMENSION) {
                addNewDatapack(event, CataclysmDimensionModConfig.KEEP_STRUCTURES_IN_ORIGINAL_DIMENSIONS ? "random_spread_dim" : "random_spread");
            }
            if (CataclysmDimensionModConfig.DISABLE_RESPAWN) {
                addNewDatapack(event, "disable_respawn");
            }
        }
    }

    private void addNewDatapack(AddPackFindersEvent event, String name) {
        var resourcePath = ModList.get().getModFileById(MOD_ID).getFile().findResource("packs/" + name);
        var pack = Pack.readMetaAndCreate(name, Component.literal(name), true,
                (path) -> new PathPackResources(path, resourcePath, false), PackType.SERVER_DATA, Pack.Position.TOP, PackSource.WORLD);
        event.addRepositorySource((packConsumer) -> packConsumer.accept(pack));
    }

}
