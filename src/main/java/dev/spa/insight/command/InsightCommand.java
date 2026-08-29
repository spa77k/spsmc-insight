package dev.spa.insight.command;

import dev.spa.insight.InsightConfig;
import dev.spa.insight.backfill.BackfillService;
import dev.spa.insight.export.ExportService;
import dev.spa.insight.source.SourceStatus;
import dev.spa.insight.source.SourceRegistry;
import dev.spa.insight.storage.Database;
import dev.spa.insight.storage.RetentionService;
import dev.spa.insight.tracking.SessionTracker;
import dev.spa.insight.util.TimeUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 運営向けのコマンド。集計と出力の実行、状態の確認だけを行う。
 */
public class InsightCommand implements CommandExecutor, TabCompleter {

    private final Plugin plugin;
    private final Database database;
    private final InsightConfig config;
    private final SessionTracker tracker;
    private final SourceRegistry sources;
    private final ExportService exportService;
    private final BackfillService backfillService;
    private final RetentionService retentionService;
    private final ExecutorService writer;

    public InsightCommand(Plugin plugin, Database database, InsightConfig config, SessionTracker tracker,
                          SourceRegistry sources, ExportService exportService, BackfillService backfillService,
                          RetentionService retentionService, ExecutorService writer) {
        this.plugin = plugin;
        this.database = database;
        this.config = config;
        this.tracker = tracker;
        this.sources = sources;
        this.exportService = exportService;
        this.backfillService = backfillService;
        this.retentionService = retentionService;
        this.writer = writer;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length == 0 ? "help" : args[0].toLowerCase(java.util.Locale.ROOT);
        switch (action) {
            case "export" -> handleExport(sender, args);
            case "backfill" -> handleBackfill(sender, args);
            case "status" -> handleStatus(sender);
            case "sources" -> handleSources(sender);
            case "flush" -> handleFlush(sender);
            case "compress" -> handleCompress(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("/insight export [週|current] - 指定した週のJSONを書き出す（既定は先週）");
        sender.sendMessage("/insight backfill [--force] - 導入前のデータを遡って復元する");
        sender.sendMessage("/insight status - 収集状況と件数を表示する");
        sender.sendMessage("/insight sources - 連携プラグインの状態を表示する");
        sender.sendMessage("/insight flush - メモリ上のカウンタを今すぐ保存する");
        sender.sendMessage("/insight compress - 保持期間を超えた明細を日次へ畳む");
    }

    private void handleExport(CommandSender sender, String[] args) {
        long now = System.currentTimeMillis();
        String week;
        if (args.length >= 2 && !args[1].equalsIgnoreCase("current")) {
            week = args[1];
        } else if (args.length >= 2) {
            week = TimeUtil.weekKey(now);
        } else {
            week = TimeUtil.previousWeekKey(now);
        }
        sender.sendMessage("エクスポートを開始します: " + week);
        tracker.flushAll();
        writer.execute(() -> {
            try {
                ExportService.ExportResult result = exportService.export(week);
                reply(sender, "エクスポート完了: " + result.directory().getPath()
                        + " files=" + result.files() + " bytes=" + result.bytes());
                for (String warning : result.warnings()) {
                    reply(sender, "注意: " + warning);
                }
            } catch (Exception e) {
                reply(sender, "エクスポートに失敗しました: " + e.getMessage());
                plugin.getLogger().warning("エクスポートに失敗しました: " + e);
            }
        });
    }

    private void handleBackfill(CommandSender sender, String[] args) {
        boolean force = args.length >= 2 && args[1].equalsIgnoreCase("--force");
        if (backfillService.alreadyDone() && !force) {
            sender.sendMessage("遡及は実行済みです: " + backfillService.lastReport());
            sender.sendMessage("もう一度実行するには /insight backfill --force");
            return;
        }
        sender.sendMessage("遡及を開始します。件数によっては時間がかかります。");
        writer.execute(() -> {
            try {
                BackfillService.Report report = backfillService.run(force);
                reply(sender, "遡及完了: " + report.describe());
            } catch (Exception e) {
                reply(sender, "遡及に失敗しました: " + e.getMessage());
                plugin.getLogger().warning("遡及に失敗しました: " + e);
            }
        });
    }

    private void handleStatus(CommandSender sender) {
        writer.execute(() -> {
            long players = database.queryLong("SELECT COUNT(*) FROM players");
            long sessions = database.queryLong("SELECT COUNT(*) FROM sessions");
            long counters = database.queryLong("SELECT COUNT(*) FROM session_counters");
            long milestones = database.queryLong("SELECT COUNT(*) FROM milestones");
            long days = database.queryLong("SELECT COUNT(*) FROM daily_activity");
            long snapshots = database.queryLong("SELECT COUNT(*) FROM external_snapshots");
            long exports = database.queryLong("SELECT COUNT(*) FROM exports");
            reply(sender, "接続中セッション: " + tracker.live().size());
            reply(sender, "players=" + players + " sessions=" + sessions + " counters=" + counters);
            reply(sender, "milestones=" + milestones + " daily_activity=" + days + " snapshots=" + snapshots);
            reply(sender, "エクスポート済みの週: " + exports + " / 出力先: "
                    + plugin.getDataFolder().getName() + "/" + config.exportDirectory());
            reply(sender, "遡及: " + (backfillService.alreadyDone()
                    ? backfillService.lastReport() : "未実行"));
        });
    }

    private void handleSources(CommandSender sender) {
        for (Map.Entry<String, SourceStatus> entry : sources.statuses().entrySet()) {
            SourceStatus status = entry.getValue();
            sender.sendMessage(entry.getKey() + ": " + status.status()
                    + (status.detail() == null || status.detail().isBlank() ? "" : " - " + status.detail()));
        }
    }

    private void handleFlush(CommandSender sender) {
        tracker.flushAll();
        writer.execute(() -> reply(sender, "メモリ上のカウンタを保存しました。"));
    }

    private void handleCompress(CommandSender sender) {
        writer.execute(() -> {
            try {
                RetentionService.Result result = retentionService.compress(config.retentionDetailDays());
                reply(sender, "圧縮しました: rollup=" + result.rolledUp()
                        + " removed_sessions=" + result.removedSessions());
            } catch (Exception e) {
                reply(sender, "圧縮に失敗しました: " + e.getMessage());
            }
        });
    }

    /**
     * DB作業は書き込みスレッドで行うため、返信はメインスレッドへ戻す。
     */
    private void reply(CommandSender sender, String message) {
        plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(message));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return List.of("export", "backfill", "status", "sources", "flush", "compress");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("export")) {
            List<String> weeks = new ArrayList<>();
            long now = System.currentTimeMillis();
            weeks.add(TimeUtil.previousWeekKey(now));
            weeks.add(TimeUtil.weekKey(now));
            weeks.add("current");
            return weeks;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("backfill")) {
            return List.of("--force");
        }
        return List.of();
    }
}
