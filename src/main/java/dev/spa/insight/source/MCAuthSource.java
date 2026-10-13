package dev.spa.insight.source;

import dev.spa.insight.milestone.Milestones;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

/**
 * MCAuth の mcauth.db を読み取り専用で読む。DBが無い旧版のみ data.yml に対応する。
 * 連携日時は保存されていないため、マイルストーンは最初の観測時刻を使う。
 */
public class MCAuthSource implements SourceAdapter {

    private final File database;
    private final File dataFile;
    private boolean databaseSelected;

    public MCAuthSource(File pluginsFolder) {
        File folder = new File(pluginsFolder, "MCAuth");
        this.database = new File(folder, "mcauth.db");
        this.dataFile = new File(folder, "data.yml");
    }

    @Override
    public String id() {
        return "mcauth";
    }

    @Override
    public SourceStatus probe() {
        try {
            if (useDatabase()) {
                try (Connection connection = SqliteReader.openReadOnly(database);
                     PreparedStatement statement = connection.prepareStatement(
                             "SELECT uuid, discord_user_id, discord_user_name FROM authenticated_players LIMIT 0");
                     ResultSet ignored = statement.executeQuery()) {
                    return SourceStatus.available(id(), database.getPath());
                }
            }
            if (dataFile.exists()) {
                loadLegacy();
                return SourceStatus.available(id(), dataFile.getPath() + " (legacy YAML)");
            }
            return SourceStatus.unavailable(id(), "mcauth.db / data.yml が見つかりません: " + database.getParent());
        } catch (Exception e) {
            // DBが壊れていても古いYAMLへ戻さない。解除済みの連携を復活させないため。
            return SourceStatus.error(id(), e);
        }
    }

    @Override
    public void collect(Sink sink) throws Exception {
        if (useDatabase()) {
            collectDatabase(sink);
            return;
        }
        ConfigurationSection authenticated = loadLegacy();
        for (UUID uuid : sink.targets()) {
            ConfigurationSection section = authenticated == null ? null
                    : authenticated.getConfigurationSection(uuid.toString());
            if (section == null) {
                recordUnlinked(sink, uuid);
                continue;
            }
            recordLinked(sink, uuid, section.getString("discord-user-id", ""),
                    section.getString("discord-user-name", ""));
        }
    }

    private boolean useDatabase() {
        // 一度DBを見つけたら、削除・置換中でも古いYAMLへ逆戻りしない。
        databaseSelected |= database.exists();
        return databaseSelected;
    }

    private void collectDatabase(Sink sink) throws Exception {
        try (Connection connection = SqliteReader.openReadOnly(database);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT discord_user_id, discord_user_name FROM authenticated_players WHERE uuid = ?")) {
            for (UUID uuid : sink.targets()) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        recordLinked(sink, uuid, result.getString("discord_user_id"),
                                result.getString("discord_user_name"));
                    } else {
                        recordUnlinked(sink, uuid);
                    }
                }
            }
        }
    }

    private ConfigurationSection loadLegacy() throws IOException, InvalidConfigurationException {
        // loadConfiguration は破損・読取失敗を空データにするので使わず、欠測として上位へ伝える。
        YamlConfiguration data = new YamlConfiguration();
        data.load(dataFile);
        if (data.contains("authenticated") && !data.isConfigurationSection("authenticated")) {
            throw new InvalidConfigurationException("authenticated がセクションではありません");
        }
        ConfigurationSection authenticated = data.getConfigurationSection("authenticated");
        if (authenticated != null) {
            for (String key : authenticated.getKeys(false)) {
                if (!authenticated.isConfigurationSection(key)) {
                    throw new InvalidConfigurationException("authenticated のプレイヤー情報がセクションではありません");
                }
            }
        }
        return authenticated;
    }

    private static void recordLinked(Sink sink, UUID uuid, String discordId, String discordName) {
        sink.value(uuid, "linked", true);
        sink.value(uuid, "discord_user_id", discordId);
        sink.value(uuid, "discord_user_name", discordName);
        sink.milestone(uuid, Milestones.FIRST_DISCORD_LINK, sink.now(), "mcauth");
    }

    private static void recordUnlinked(Sink sink, UUID uuid) {
        sink.value(uuid, "linked", false);
        // 前の取り込みで保存した連携先を残さない。
        sink.value(uuid, "discord_user_id", "");
        sink.value(uuid, "discord_user_name", "");
    }
}
