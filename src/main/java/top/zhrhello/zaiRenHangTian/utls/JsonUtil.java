package top.zhrhello.zaiRenHangTian.utls;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class JsonUtil {
    // 加载权限文件
    public static Map<UUID, Boolean> loadPermissions(@NotNull File file) throws IOException {
        Map<UUID, Boolean> map = new HashMap<>();
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        for (String key : config.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                boolean isBlacklisted = config.getBoolean(key);
                map.put(uuid, isBlacklisted);
            } catch (IllegalArgumentException ignored) {}
        }
        return map;
    }

    // 保存权限文件
    public static void savePermissions(@NotNull File file, @NotNull Map<UUID, Boolean> map) throws IOException {
        YamlConfiguration config = new YamlConfiguration();

        for (Map.Entry<UUID, Boolean> entry : map.entrySet()) {
            config.set(entry.getKey().toString(), entry.getValue());
        }

        config.save(file);
    }
}