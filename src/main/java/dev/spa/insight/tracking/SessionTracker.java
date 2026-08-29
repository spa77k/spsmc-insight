package dev.spa.insight.tracking;

import dev.spa.insight.InsightConfig;
import dev.spa.insight.milestone.MilestoneRecorder;
import dev.spa.insight.milestone.Milestones;
import dev.spa.insight.model.LiveSession;
import dev.spa.insight.storage.Database;
import dev.spa.insight.storage.PlayerStore;
import dev.spa.insight.storage.SessionStore;
import dev.spa.insight.util.TimeUtil;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

/**
 * 接続中セッションの管理と、DBへの書き出しの入口。
 * リスナーはここのメソッドを呼ぶだけで、SQLには触れない。
 */
public class SessionTracker {

    private static final long HOUR_MILLIS = 3_600_000L;

    private final Database database;
    private final PlayerStore playerStore;
    private final SessionStore sessionStore;
    private final MilestoneRecorder milestones;
    private final InsightConfig config;
    private final ExecutorService writer;
    private final Logger logger;

    private final Map<UUID, LiveSession> live = new ConcurrentHashMap<>();

    public SessionTracker(Database database, PlayerStore playerStore, SessionStore sessionStore,
                          MilestoneRecorder milestones, InsightConfig config, ExecutorService writer, Logger logger) {
        this.database = database;
        this.playerStore = playerStore;
        this.sessionStore = sessionStore;
        this.milestones = milestones;
        this.config = config;
        this.writer = writer;
        this.logger = logger;
    }

    public LiveSession session(Player player) {
        return player == null ? null : live.get(player.getUniqueId());
    }

    public Map<UUID, LiveSession> live() {
        return live;
    }

    public MilestoneRecorder milestones() {
        return milestones;
    }

    /**
     * 自発的な操作の記録。アクティブ時間の判定はここに集約する。
     */
    public void touch(Player player) {
        LiveSession session = session(player);
        if (session != null) {
            session.touch(System.currentTimeMillis(), config.activeTimeoutMillis());
        }
    }

    public void onJoin(Player player) {
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        String world = player.getWorld().getName();
        LiveSession session = new LiveSession(uuid, player.getName(), now, TimeUtil.dayKey(now), world);
        session.position(player.getLocation().getX(), player.getLocation().getY(), player.getLocation().getZ());
        live.put(uuid, session);

        writer.execute(() -> {
            try {
                milestones.warmUp(uuid);
                playerStore.seenAt(uuid, player.getName(), now, "live");
                PlayerStore.PlayerInfo info = playerStore.load(uuid);
                int previous = playerStore.countPreviousSessions(uuid, now);
                long firstSeen = info == null ? now : info.firstSeen();
                long daysSinceFirst = TimeUtil.daysBetween(TimeUtil.dayKey(firstSeen), TimeUtil.dayKey(now));
                session.cohort(previous == 0, previous + 1, daysSinceFirst);

                long id = sessionStore.insert(session);
                session.sessionId(id);
                playerStore.countSession(uuid);
                sessionStore.addDailyActivity(uuid, session.day(), 1, 0L, 0L);

                milestones.recordDirect(uuid, Milestones.FIRST_JOIN, firstSeen, "live", null);
                int index = previous + 1;
                if (index == 2) {
                    milestones.recordDirect(uuid, Milestones.SECOND_SESSION, now, "live", null);
                }
                if (index == 5) {
                    milestones.recordDirect(uuid, Milestones.FIFTH_SESSION, now, "live", null);
                }
                if (index == 10) {
                    milestones.recordDirect(uuid, Milestones.TENTH_SESSION, now, "live", null);
                }
                if (daysSinceFirst >= 1) {
                    milestones.recordDirect(uuid, Milestones.RETURNED_NEXT_DAY, now, "live",
                            "days_since_first=" + daysSinceFirst);
                }
            } catch (SQLException e) {
                logger.warning("接続の記録に失敗しました (" + player.getName() + "): " + e.getMessage());
            }
        });
    }

    public void onQuit(Player player, String reason) {
        LiveSession session = live.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        long now = System.currentTimeMillis();
        milestones.forget(player.getUniqueId());
        writer.execute(() -> {
            try {
                long durationDelta = 0L;
                long activeDelta = 0L;
                if (session.sessionId() >= 0) {
                    durationDelta = now - session.joinAt();
                    activeDelta = session.activeMillis();
                }
                sessionStore.finish(session, now, reason);
                playerStore.addTotals(session.uuid(), durationDelta, activeDelta, now);
                checkPlaytimeMilestones(session.uuid(), now);
            } catch (SQLException e) {
                logger.warning("切断の記録に失敗しました (" + player.getName() + "): " + e.getMessage());
            }
        });
    }

    /**
     * 定期フラッシュ。接続中の全セッションの途中経過を書き出す。
     */
    public void flushAll() {
        long now = System.currentTimeMillis();
        writer.execute(() -> {
            for (LiveSession session : live.values()) {
                if (session.sessionId() < 0) {
                    continue;
                }
                try {
                    sessionStore.flush(session, now);
                } catch (SQLException e) {
                    logger.warning("セッションのフラッシュに失敗しました (" + session.name() + "): " + e.getMessage());
                }
            }
        });
    }

    /**
     * 停止時の同期フラッシュ。書き込みスレッドを待たずにこのスレッドで閉じる。
     */
    public void flushAndCloseAll(String reason) {
        long now = System.currentTimeMillis();
        for (LiveSession session : live.values()) {
            try {
                sessionStore.finish(session, now, reason);
                playerStore.addTotals(session.uuid(), now - session.joinAt(), session.activeMillis(), now);
            } catch (SQLException e) {
                logger.warning("停止時のフラッシュに失敗しました (" + session.name() + "): " + e.getMessage());
            }
        }
        live.clear();
    }

    private void checkPlaytimeMilestones(UUID uuid, long now) throws SQLException {
        long total = database.queryLong("SELECT total_playtime_ms FROM players WHERE uuid = ?", uuid.toString());
        if (total >= HOUR_MILLIS) {
            milestones.recordDirect(uuid, Milestones.PLAYTIME_1H, now, "live", null);
        }
        if (total >= 10 * HOUR_MILLIS) {
            milestones.recordDirect(uuid, Milestones.PLAYTIME_10H, now, "live", null);
        }
        if (total >= 50 * HOUR_MILLIS) {
            milestones.recordDirect(uuid, Milestones.PLAYTIME_50H, now, "live", null);
        }
    }
}
