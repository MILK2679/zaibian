package com.p1nero.cataclysm_dimension;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class CataclysmDimensionModConfig {
    // ===== 原有配置项 =====
    public static boolean ENABLE_TELEPORT_EYE = true;
    public static boolean KEEP_STRUCTURES_IN_ORIGINAL_DIMENSIONS = false;
    public static boolean RANDOM_SPREAD_IN_DIMENSION = false;
    public static boolean RESET_DIMENSION_IF_NO_PLAYER = false;
    public static boolean SLOW_FALL_WHEN_ENTER_DIMENSIONS = true;
    public static boolean DISABLE_RESPAWN = false;

    // ===== 新增：每玩家独立实例 =====
    /** 总开关。开启后，玩家用眼睛进入时会给他单独开一个动态维度（独立文件夹）。 */
    public static boolean PER_PLAYER_INSTANCE = true;
    /** true = 每次用眼睛都生成崭新实例(老实例留给自动清理)；false = 复用该玩家的旧实例(若还在)。 */
    public static boolean FRESH_INSTANCE_EACH_TIME = true;
    /** 实例空人后多少 tick 开始卸载+删文件夹。默认 1200 tick = 60 秒。 */
    public static int INSTANCE_CLEANUP_DELAY_TICKS = 1200;

    // ===== 配置键名常量 =====
    private static final String ENABLE_TELEPORT_EYE_KEY = "enable_teleport_eye";
    private static final String KEEP_STRUCTURES_KEY = "keep_structures_in_original_dimensions";
    private static final String RANDOM_SPREAD_KEY = "random_spread_in_dimension";
    private static final String RESET_DIMENSION_KEY = "reset_dimension_if_no_player";
    private static final String SLOW_FALL_WHEN_ENTER_DIMENSIONS_KEY = "slow_fall_when_enter_dimensions";
    private static final String DISABLE_RESPAWN_KEY = "disable_respawn";
    private static final String PER_PLAYER_INSTANCE_KEY = "per_player_instance";
    private static final String FRESH_INSTANCE_EACH_TIME_KEY = "fresh_instance_each_time";
    private static final String INSTANCE_CLEANUP_DELAY_TICKS_KEY = "instance_cleanup_delay_ticks";

    public static final String JSON = CataclysmDimensionMod.MOD_ID + ".json";
    public static final Logger LOGGER = LoggerFactory.getLogger("cataclysm_dimension_config");

    // 默认配置映射（用 LinkedHashMap 保留插入顺序，生成的 json 更好看）
    private static final Map<String, Object> DEFAULT_CONFIG = new LinkedHashMap<>();
    static {
        DEFAULT_CONFIG.put(ENABLE_TELEPORT_EYE_KEY, ENABLE_TELEPORT_EYE);
        DEFAULT_CONFIG.put(KEEP_STRUCTURES_KEY, KEEP_STRUCTURES_IN_ORIGINAL_DIMENSIONS);
        DEFAULT_CONFIG.put(RANDOM_SPREAD_KEY, RANDOM_SPREAD_IN_DIMENSION);
        DEFAULT_CONFIG.put(RESET_DIMENSION_KEY, RESET_DIMENSION_IF_NO_PLAYER);
        DEFAULT_CONFIG.put(SLOW_FALL_WHEN_ENTER_DIMENSIONS_KEY, SLOW_FALL_WHEN_ENTER_DIMENSIONS);
        DEFAULT_CONFIG.put(DISABLE_RESPAWN_KEY, DISABLE_RESPAWN);
        DEFAULT_CONFIG.put(PER_PLAYER_INSTANCE_KEY, PER_PLAYER_INSTANCE);
        DEFAULT_CONFIG.put(FRESH_INSTANCE_EACH_TIME_KEY, FRESH_INSTANCE_EACH_TIME);
        DEFAULT_CONFIG.put(INSTANCE_CLEANUP_DELAY_TICKS_KEY, INSTANCE_CLEANUP_DELAY_TICKS);
    }

    public static void loadConfig() {
        File configFolder = new File("config", CataclysmDimensionMod.MOD_ID);
        File configFile = new File(configFolder, JSON);

        if (!configFolder.exists() && !configFolder.mkdirs()) {
            LOGGER.error("Failed to create config folder: {}", configFolder.getAbsolutePath());
            return;
        }

        if (!configFile.exists()) {
            generateConfig(configFile);
            return;
        }

        try (FileReader reader = new FileReader(configFile)) {
            JsonObject config = new Gson().fromJson(reader, JsonObject.class);
            boolean needsUpdate = false;

            // 补齐缺失的配置项（用于 mod 升级后平滑迁移）
            for (Map.Entry<String, Object> entry : DEFAULT_CONFIG.entrySet()) {
                String key = entry.getKey();
                if (!config.has(key)) {
                    LOGGER.info("Adding missing config key: {}", key);
                    Object val = entry.getValue();
                    if (val instanceof Boolean b) {
                        config.addProperty(key, b);
                    } else if (val instanceof Number n) {
                        config.addProperty(key, n);
                    }
                    needsUpdate = true;
                }
            }

            if (needsUpdate) {
                try (FileWriter writer = new FileWriter(configFile)) {
                    writer.write(new GsonBuilder().setPrettyPrinting().create().toJson(config));
                }
            }

            // 读值
            ENABLE_TELEPORT_EYE = config.get(ENABLE_TELEPORT_EYE_KEY).getAsBoolean();
            RANDOM_SPREAD_IN_DIMENSION = config.get(RANDOM_SPREAD_KEY).getAsBoolean();
            KEEP_STRUCTURES_IN_ORIGINAL_DIMENSIONS = config.get(KEEP_STRUCTURES_KEY).getAsBoolean();
            RESET_DIMENSION_IF_NO_PLAYER = config.get(RESET_DIMENSION_KEY).getAsBoolean();
            SLOW_FALL_WHEN_ENTER_DIMENSIONS = config.get(SLOW_FALL_WHEN_ENTER_DIMENSIONS_KEY).getAsBoolean();
            DISABLE_RESPAWN = config.get(DISABLE_RESPAWN_KEY).getAsBoolean();
            PER_PLAYER_INSTANCE = config.get(PER_PLAYER_INSTANCE_KEY).getAsBoolean();
            FRESH_INSTANCE_EACH_TIME = config.get(FRESH_INSTANCE_EACH_TIME_KEY).getAsBoolean();
            INSTANCE_CLEANUP_DELAY_TICKS = config.get(INSTANCE_CLEANUP_DELAY_TICKS_KEY).getAsInt();

        } catch (IOException e) {
            LOGGER.error("Failed to load configuration file: {}", e.getMessage());
            useDefaultValues();
        } catch (Exception e) {
            LOGGER.error("Error parsing configuration file: {}", e.getMessage());
            generateConfig(configFile);
            useDefaultValues();
        }
    }

    private static void generateConfig(File configFile) {
        try {
            if (configFile.createNewFile()) {
                LOGGER.info("Generating configuration file: {}", configFile.getAbsolutePath());

                JsonObject config = new JsonObject();
                for (Map.Entry<String, Object> entry : DEFAULT_CONFIG.entrySet()) {
                    Object val = entry.getValue();
                    if (val instanceof Boolean b) {
                        config.addProperty(entry.getKey(), b);
                    } else if (val instanceof Number n) {
                        config.addProperty(entry.getKey(), n);
                    }
                }

                try (FileWriter writer = new FileWriter(configFile)) {
                    writer.write(new GsonBuilder().setPrettyPrinting().create().toJson(config));
                }
            } else {
                LOGGER.error("Failed to create configuration file: {}", configFile.getAbsolutePath());
            }
        } catch (IOException e) {
            LOGGER.error("Error generating configuration file: {}", e.getMessage());
        }
    }

    private static void useDefaultValues() {
        ENABLE_TELEPORT_EYE = (Boolean) DEFAULT_CONFIG.get(ENABLE_TELEPORT_EYE_KEY);
        RANDOM_SPREAD_IN_DIMENSION = (Boolean) DEFAULT_CONFIG.get(RANDOM_SPREAD_KEY);
        KEEP_STRUCTURES_IN_ORIGINAL_DIMENSIONS = (Boolean) DEFAULT_CONFIG.get(KEEP_STRUCTURES_KEY);
        RESET_DIMENSION_IF_NO_PLAYER = (Boolean) DEFAULT_CONFIG.get(RESET_DIMENSION_KEY);
        SLOW_FALL_WHEN_ENTER_DIMENSIONS = (Boolean) DEFAULT_CONFIG.get(SLOW_FALL_WHEN_ENTER_DIMENSIONS_KEY);
        DISABLE_RESPAWN = (Boolean) DEFAULT_CONFIG.get(DISABLE_RESPAWN_KEY);
        PER_PLAYER_INSTANCE = (Boolean) DEFAULT_CONFIG.get(PER_PLAYER_INSTANCE_KEY);
        FRESH_INSTANCE_EACH_TIME = (Boolean) DEFAULT_CONFIG.get(FRESH_INSTANCE_EACH_TIME_KEY);
        INSTANCE_CLEANUP_DELAY_TICKS = ((Number) DEFAULT_CONFIG.get(INSTANCE_CLEANUP_DELAY_TICKS_KEY)).intValue();
    }
}
