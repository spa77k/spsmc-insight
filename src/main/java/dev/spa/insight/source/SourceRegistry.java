package dev.spa.insight.source;

import dev.spa.insight.InsightConfig;
import dev.spa.insight.milestone.MilestoneRecorder;
import dev.spa.insight.storage.Database;

import java.io.File;
import java.util.Collections;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * 連携ソースの束ね役。1つが落ちても他は動かす。
 */
public class SourceRegistry {

    private final Database database;
    private final InsightConfig config;
    private final MilestoneRecorder milestones;
    private final Logger logger;

    private final List<SourceAdapter> adapters = new ArrayList<>();
    /** 取り込みは書き込みスレッド、表示はメインスレッドから読むため同期化する。 */
    private final Map<String, SourceStatus> statuses =
            Collections.synchronizedMap(new LinkedHashMap<String, SourceStatus>());
    private CoreProtectSource coreProtect;
    private EssentialsSource essentials;

    public SourceRegistry(Database database, InsightConfig config, MilestoneRecorder milestones, Logger logger) {
        this.database = database;
        this.config = config;
        this.milestones = milestones;
        this.logger = logger;
    }

    public void discover(File pluginsFolder) {
        coreProtect = new CoreProtectSource(pluginsFolder);
        essentials = new EssentialsSource(pluginsFolder);
        register(() -> coreProtect);
        register(() -> essentials);
        register(() -> new McLevelSource(pluginsFolder));
        register(() -> new MCAuthSource(pluginsFolder));
        register(() -> new ContractBoardSource(pluginsFolder));
        register(ServiceSources.Vault::new);
        register(ServiceSources.LuckPerms::new);
        register(PluginApiSources.Jobs::new);
        register(PluginApiSources.GriefPrevention::new);
        register(PluginApiSources.QuickShop::new);
        register(PluginApiSources.Bolt::new);
        register(PluginApiSources.DiscordSrv::new);
        register(PluginApiSources.WorldGuard::new);
        register(PluginApiSources.Multiverse::new);
    }

    /**
     * 生成そのものが失敗しても他のソースを巻き込まないよう、必ず個別に包む。
     */
    private void register(Supplier<SourceAdapter> factory) {
        SourceAdapter adapter;
        try {
            adapter = factory.get();
        } catch (RuntimeException | LinkageError e) {
            logger.warning("連携ソースを初期化できませんでした: " + e);
            return;
        }
        adapters.add(adapter);
        SourceStatus status;
        if (!config.sourceEnabled(adapter.id())) {
            status = SourceStatus.disabled(adapter.id());
        } else {
            try {
                status = adapter.probe();
            } catch (RuntimeException | LinkageError e) {
                status = SourceStatus.error(adapter.id(), String.valueOf(e));
            }
        }
        statuses.put(adapter.id(), status);
        database.recordSourceStatus(status.id(), status.status(), status.detail());
    }

    /**
     * 反復中に取り込みスレッドが更新しても壊れないよう、複製を返す。
     */
    public Map<String, SourceStatus> statuses() {
        synchronized (statuses) {
            return new LinkedHashMap<>(statuses);
        }
    }

    public CoreProtectSource coreProtect() {
        return coreProtect;
    }

    public EssentialsSource essentials() {
        return essentials;
    }

    /**
     * 現在値の取り込み。書き込みスレッド上から呼ぶこと。
     */
    public void collectAll(Collection<UUID> targets) {
        if (targets.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (SourceAdapter adapter : adapters) {
            SourceStatus status = statuses.get(adapter.id());
            if (status == null || !status.usable()) {
                continue;
            }
            RecordingSink sink = new RecordingSink(targets, now, adapter.id());
            try {
                adapter.collect(sink);
                sink.commit();
            } catch (Exception | LinkageError e) {
                SourceStatus failed = SourceStatus.error(adapter.id(), String.valueOf(e));
                statuses.put(adapter.id(), failed);
                database.recordSourceStatus(failed.id(), failed.status(), failed.detail());
                logger.warning("連携ソースの取り込みに失敗しました (" + adapter.id() + "): " + e);
            }
        }
    }

    /**
     * 取り込んだ値を溜めてから1トランザクションで書く。
     */
    private final class RecordingSink implements SourceAdapter.Sink {

        private final Collection<UUID> targets;
        private final long now;
        private final String source;
        private final List<Object[]> values = new ArrayList<>();

        private RecordingSink(Collection<UUID> targets, long now, String source) {
            this.targets = targets;
            this.now = now;
            this.source = source;
        }

        @Override
        public Collection<UUID> targets() {
            return targets;
        }

        @Override
        public long now() {
            return now;
        }

        @Override
        public void value(UUID uuid, String key, Object value) {
            values.add(new Object[]{uuid.toString(), source, key, value == null ? null : value.toString(), now});
        }

        @Override
        public void milestone(UUID uuid, String key, long achievedAt, String detail) {
            milestones.record(uuid, key, achievedAt, source, detail);
        }

        private void commit() throws SQLException {
            if (values.isEmpty()) {
                return;
            }
            database.inTransaction(connection -> {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO external_snapshots (uuid, source, key, value, taken_at) VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT(uuid, source, key) DO UPDATE SET value = excluded.value,
                                                                    taken_at = excluded.taken_at
                        """)) {
                    for (Object[] row : values) {
                        statement.setString(1, (String) row[0]);
                        statement.setString(2, (String) row[1]);
                        statement.setString(3, (String) row[2]);
                        statement.setString(4, (String) row[3]);
                        statement.setLong(5, (Long) row[4]);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
            });
        }
    }
}
