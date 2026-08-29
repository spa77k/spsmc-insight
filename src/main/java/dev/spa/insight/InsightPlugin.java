package dev.spa.insight;

import dev.spa.insight.backfill.BackfillService;
import dev.spa.insight.command.InsightCommand;
import dev.spa.insight.export.ExportService;
import dev.spa.insight.listener.BlockListener;
import dev.spa.insight.listener.ChatListener;
import dev.spa.insight.listener.CombatListener;
import dev.spa.insight.listener.InventoryListener;
import dev.spa.insight.listener.MovementListener;
import dev.spa.insight.listener.ProgressionListener;
import dev.spa.insight.listener.SessionListener;
import dev.spa.insight.milestone.MilestoneRecorder;
import dev.spa.insight.source.SourceRegistry;
import dev.spa.insight.storage.Database;
import dev.spa.insight.storage.PlayerStore;
import dev.spa.insight.storage.RetentionService;
import dev.spa.insight.storage.SessionStore;
import dev.spa.insight.tracking.SessionTracker;
import dev.spa.insight.util.TimeUtil;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class InsightPlugin extends JavaPlugin {

    private static final String LAST_EXPORT_KEY = "last_exported_week";

    private Database database;
    private ExecutorService writer;
    private SessionTracker tracker;
    private SourceRegistry sources;
    private ExportService exportService;
    private BackfillService backfillService;
    private RetentionService retentionService;
    private InsightConfig insightConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        insightConfig = new InsightConfig(getConfig());

        database = new Database(this);
        try {
            database.connect();
        } catch (Exception e) {
            getLogger().severe("データベースを開けませんでした。プラグインを無効化します: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SPSMCInsight-writer");
            thread.setDaemon(true);
            return thread;
        });

        PlayerStore playerStore = new PlayerStore(database);
        SessionStore sessionStore = new SessionStore(database, insightConfig.materialBreakdownTop());
        MilestoneRecorder milestones = new MilestoneRecorder(database, writer, getLogger());
        tracker = new SessionTracker(database, playerStore, sessionStore, milestones, insightConfig, writer,
                getLogger());
        retentionService = new RetentionService(database, getLogger());

        try {
            int orphans = sessionStore.closeOrphans();
            if (orphans > 0) {
                getLogger().info("前回の停止時に開いたままだったセッションを閉じました: " + orphans);
            }
        } catch (Exception e) {
            getLogger().warning("未終了セッションの整理に失敗しました: " + e.getMessage());
        }

        // getDataFolder() は相対パスのことがあり、親をたどると null になる。先に絶対パスへ直す。
        File pluginsFolder = getDataFolder().getAbsoluteFile().getParentFile();
        sources = new SourceRegistry(database, insightConfig, milestones, getLogger());
        sources.discover(pluginsFolder);

        exportService = new ExportService(database, insightConfig, sources, getDataFolder(), getLogger());
        backfillService = new BackfillService(database, playerStore, milestones, sources, insightConfig,
                mainWorldFolder(), serverRoot(pluginsFolder), getLogger());

        registerListeners();
        registerCommand();
        scheduleTasks();

        if (insightConfig.backfillEnabled() && !backfillService.alreadyDone()) {
            writer.execute(() -> {
                try {
                    getLogger().info("導入前データの遡及を開始します。");
                    backfillService.run(false);
                } catch (Exception e) {
                    getLogger().warning("遡及に失敗しました: " + e.getMessage());
                }
            });
        }

        getLogger().info("SPSMCInsight を有効化しました。連携ソース: " + sources.statuses().size() + "件");
    }

    @Override
    public void onDisable() {
        if (writer != null) {
            writer.shutdown();
            try {
                if (!writer.awaitTermination(15, TimeUnit.SECONDS)) {
                    getLogger().warning("書き込みスレッドの終了を待てませんでした。");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (tracker != null) {
            tracker.flushAndCloseAll("server_stop");
        }
        if (database != null) {
            database.close();
        }
    }

    /**
     * リスナーは1つずつ登録する。イベントAPIの差でどれかが失敗しても、残りは動かす。
     */
    private void registerListeners() {
        register("session", () -> new SessionListener(tracker));
        if (insightConfig.tracking("blocks") || insightConfig.tracking("interactions")) {
            register("block", () -> new BlockListener(tracker));
        }
        if (insightConfig.tracking("combat") || insightConfig.tracking("deaths")) {
            register("combat", () -> new CombatListener(tracker));
        }
        if (insightConfig.tracking("chat") || insightConfig.tracking("commands")) {
            register("chat", () -> new ChatListener(tracker));
        }
        if (insightConfig.tracking("inventory") || insightConfig.tracking("crafting")) {
            register("inventory", () -> new InventoryListener(tracker));
        }
        if (insightConfig.tracking("movement")) {
            register("movement", () -> new MovementListener(tracker, insightConfig.activeTimeoutMillis()));
        }
        if (insightConfig.tracking("advancements")) {
            register("progression", () -> new ProgressionListener(tracker));
        }
    }

    private void register(String name, Supplier<Listener> factory) {
        try {
            getServer().getPluginManager().registerEvents(factory.get(), this);
        } catch (RuntimeException | LinkageError e) {
            getLogger().warning("リスナーを登録できませんでした (" + name + "): " + e
                    + " この分類のデータは欠測になります。");
        }
    }

    private void registerCommand() {
        InsightCommand executor = new InsightCommand(this, database, insightConfig, tracker, sources,
                exportService, backfillService, retentionService, writer);
        var command = getCommand("insight");
        if (command == null) {
            getLogger().warning("insight コマンドを登録できませんでした。");
            return;
        }
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void scheduleTasks() {
        long flushTicks = insightConfig.flushIntervalTicks();
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> tracker.flushAll(), flushTicks, flushTicks);

        // 外部プラグインの現在値は5分ごとに取り込む。相手に負荷をかけないための間隔。
        getServer().getScheduler().runTaskTimer(this, () -> {
            List<UUID> targets = new ArrayList<>();
            for (Player player : getServer().getOnlinePlayers()) {
                targets.add(player.getUniqueId());
            }
            if (targets.isEmpty()) {
                return;
            }
            writer.execute(() -> sources.collectAll(targets));
        }, 20L * 60L, 20L * 300L);

        if (insightConfig.exportEnabled()) {
            getServer().getScheduler().runTaskTimerAsynchronously(this, this::maybeExport, 20L * 120L, 20L * 300L);
        }

        if (insightConfig.retentionEnabled()) {
            getServer().getScheduler().runTaskTimerAsynchronously(this, () -> writer.execute(() -> {
                try {
                    retentionService.compress(insightConfig.retentionDetailDays());
                } catch (Exception e) {
                    getLogger().warning("保持期間の圧縮に失敗しました: " + e.getMessage());
                }
            }), 20L * 600L, 20L * 60L * 60L * 24L);
        }
    }

    /**
     * 先週分が未出力で、かつ設定した時刻を過ぎていれば書き出す。
     */
    private void maybeExport() {
        long now = System.currentTimeMillis();
        String target = TimeUtil.previousWeekKey(now);
        ZonedDateTime boundary = TimeUtil.at(TimeUtil.weekEndMillis(target))
                .withHour(insightConfig.exportHour())
                .withMinute(insightConfig.exportMinute());
        if (now < boundary.toInstant().toEpochMilli()) {
            return;
        }
        tracker.flushAll();
        // 実行済みかどうかの判定もDBを読むため、書き込みスレッド上で行う。
        writer.execute(() -> {
            if (target.equals(database.getMeta(LAST_EXPORT_KEY, ""))) {
                return;
            }
            try {
                exportService.export(target);
                database.setMeta(LAST_EXPORT_KEY, target);
            } catch (Exception e) {
                getLogger().warning("週次エクスポートに失敗しました (" + target + "): " + e.getMessage());
            }
        });
    }

    /**
     * stats / advancements / playerdata が置かれているワールドの最上位フォルダ。
     * Paper 26.x の getWorldFolder() は dimensions/minecraft/overworld を返すため、
     * level.dat のある階層まで遡って本体を特定する。
     */
    private File mainWorldFolder() {
        List<World> worlds = getServer().getWorlds();
        if (worlds.isEmpty()) {
            return getDataFolder().getAbsoluteFile();
        }
        File current = worlds.get(0).getWorldFolder().getAbsoluteFile();
        for (int depth = 0; current != null && depth < 6; depth++) {
            if (new File(current, "level.dat").isFile()) {
                return current;
            }
            current = current.getParentFile();
        }
        return worlds.get(0).getWorldFolder().getAbsoluteFile();
    }

    /**
     * usercache.json が置かれているサーバーのルート。plugins/ の親にあたる。
     */
    private File serverRoot(File pluginsFolder) {
        File parent = pluginsFolder.getParentFile();
        return parent == null ? pluginsFolder : parent;
    }
}
