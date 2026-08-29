package dev.spa.insight.source;

import dev.spa.insight.milestone.Milestones;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.UUID;

/**
 * MCAuth の plugins/MCAuth/data.yml を読む。
 * 形式は authenticated.&lt;UUID&gt;.{name, discord-user-id, discord-user-name}。
 * Discord認証はサーバー参加の前提なので、到達時刻はファネルの起点側に置く。
 */
public class MCAuthSource implements SourceAdapter {

    private final File dataFile;

    public MCAuthSource(File pluginsFolder) {
        this.dataFile = new File(new File(pluginsFolder, "MCAuth"), "data.yml");
    }

    @Override
    public String id() {
        return "mcauth";
    }

    @Override
    public SourceStatus probe() {
        if (!dataFile.isFile()) {
            return SourceStatus.unavailable(id(), "data.yml が見つかりません: " + dataFile.getPath());
        }
        return SourceStatus.available(id(), dataFile.getPath());
    }

    @Override
    public void collect(Sink sink) {
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection authenticated = data.getConfigurationSection("authenticated");
        if (authenticated == null) {
            return;
        }
        for (UUID uuid : sink.targets()) {
            ConfigurationSection section = authenticated.getConfigurationSection(uuid.toString());
            if (section == null) {
                sink.value(uuid, "linked", false);
                continue;
            }
            sink.value(uuid, "linked", true);
            sink.value(uuid, "discord_user_id", section.getString("discord-user-id", ""));
            sink.value(uuid, "discord_user_name", section.getString("discord-user-name", ""));
            sink.milestone(uuid, Milestones.FIRST_DISCORD_LINK, sink.now(), "mcauth");
        }
    }
}
