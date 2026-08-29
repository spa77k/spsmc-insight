package dev.spa.insight.source;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.UUID;

/**
 * EssentialsX の plugins/Essentials/userdata/&lt;UUID&gt;.yml を読む。
 * 所持金・ホーム数・最終ログアウトを取る。APIを経由しないのでバージョン差に強い。
 */
public class EssentialsSource implements SourceAdapter {

    private final File userdata;

    public EssentialsSource(File pluginsFolder) {
        this.userdata = new File(new File(pluginsFolder, "Essentials"), "userdata");
    }

    @Override
    public String id() {
        return "essentials";
    }

    @Override
    public SourceStatus probe() {
        if (!userdata.isDirectory()) {
            return SourceStatus.unavailable(id(), "userdata がありません: " + userdata.getPath());
        }
        String[] files = userdata.list();
        return SourceStatus.available(id(), "userdata=" + (files == null ? 0 : files.length) + "件");
    }

    @Override
    public void collect(Sink sink) {
        for (UUID uuid : sink.targets()) {
            File file = new File(userdata, uuid + ".yml");
            if (!file.isFile()) {
                continue;
            }
            YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
            if (data.contains("money")) {
                sink.value(uuid, "money", data.getString("money", "0"));
            }
            ConfigurationSection homes = data.getConfigurationSection("homes");
            sink.value(uuid, "homes", homes == null ? 0 : homes.getKeys(false).size());
            if (data.contains("timestamps.logout")) {
                sink.value(uuid, "last_logout", data.getLong("timestamps.logout", 0L));
            }
            if (data.contains("timestamps.login")) {
                sink.value(uuid, "last_login", data.getLong("timestamps.login", 0L));
            }
            if (data.contains("last-account-name")) {
                sink.value(uuid, "last_account_name", data.getString("last-account-name", ""));
            }
        }
    }

    public File userdataFolder() {
        return userdata;
    }
}
