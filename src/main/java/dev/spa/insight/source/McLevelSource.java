package dev.spa.insight.source;

import dev.spa.insight.milestone.Milestones;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.UUID;

/**
 * McLevel の plugins/McLevel/data.yml を読む。
 * 形式は players.&lt;UUID&gt;.{name, level, activeSeconds}。
 */
public class McLevelSource implements SourceAdapter {

    private final File dataFile;

    public McLevelSource(File pluginsFolder) {
        this.dataFile = new File(new File(pluginsFolder, "McLevel"), "data.yml");
    }

    @Override
    public String id() {
        return "mclevel";
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
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (UUID uuid : sink.targets()) {
            ConfigurationSection section = players.getConfigurationSection(uuid.toString());
            if (section == null) {
                continue;
            }
            int level = section.getInt("level", 0);
            long activeSeconds = section.getLong("activeSeconds", 0L);
            sink.value(uuid, "level", level);
            sink.value(uuid, "active_seconds", activeSeconds);
            if (level >= 1) {
                sink.milestone(uuid, Milestones.LEVEL_1, sink.now(), "level=" + level);
            }
            if (level >= 2) {
                sink.milestone(uuid, Milestones.LEVEL_2, sink.now(), "level=" + level);
            }
            if (level >= 3) {
                sink.milestone(uuid, Milestones.LEVEL_3, sink.now(), "level=" + level);
            }
        }
    }
}
