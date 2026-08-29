package dev.spa.insight.model;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 接続中の1セッション。イベントハンドラからはここへ加算するだけで、DBへは書かない。
 */
public class LiveSession {

    private final UUID uuid;
    private final String name;
    private final long joinAt;
    private final String day;
    private final String joinWorld;

    private volatile boolean firstSession;
    private volatile int sessionIndex;
    private volatile long daysSinceFirst;

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> worldMillis = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> breakdown = new ConcurrentHashMap<>();

    private final AtomicLong activeMillis = new AtomicLong();
    private final AtomicLong lastActivityAt = new AtomicLong();
    private final AtomicLong flushedActiveMillis = new AtomicLong();
    private final AtomicLong flushedDurationMillis = new AtomicLong();
    private final AtomicLong sessionId = new AtomicLong(-1L);

    private volatile String currentWorld;
    private volatile long worldSince;
    private volatile double lastX;
    private volatile double lastY;
    private volatile double lastZ;
    private volatile boolean positionKnown;

    public LiveSession(UUID uuid, String name, long joinAt, String day, String joinWorld) {
        this.uuid = uuid;
        this.name = name;
        this.joinAt = joinAt;
        this.day = day;
        this.joinWorld = joinWorld;
        this.currentWorld = joinWorld;
        this.worldSince = joinAt;
        this.lastActivityAt.set(joinAt);
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public long joinAt() {
        return joinAt;
    }

    public String day() {
        return day;
    }

    public boolean firstSession() {
        return firstSession;
    }

    public int sessionIndex() {
        return sessionIndex;
    }

    public long daysSinceFirst() {
        return daysSinceFirst;
    }

    /**
     * コホート情報は接続直後に非同期で確定するため、あとから差し替える。
     */
    public void cohort(boolean firstSession, int sessionIndex, long daysSinceFirst) {
        this.firstSession = firstSession;
        this.sessionIndex = sessionIndex;
        this.daysSinceFirst = daysSinceFirst;
    }

    public String joinWorld() {
        return joinWorld;
    }

    public String currentWorld() {
        return currentWorld;
    }

    public long sessionId() {
        return sessionId.get();
    }

    public void sessionId(long id) {
        sessionId.set(id);
    }

    public void add(String metric, long amount) {
        if (amount == 0L) {
            return;
        }
        counters.computeIfAbsent(metric, key -> new AtomicLong()).addAndGet(amount);
    }

    public void increment(String metric) {
        add(metric, 1L);
    }

    public void addBreakdown(String action, String key, long amount) {
        if (key == null || key.isBlank()) {
            return;
        }
        breakdown.computeIfAbsent(action + "/" + key, ignored -> new AtomicLong()).addAndGet(amount);
    }

    public Map<String, AtomicLong> counters() {
        return counters;
    }

    public Map<String, AtomicLong> breakdown() {
        return breakdown;
    }

    public Map<String, AtomicLong> worldMillis() {
        return worldMillis;
    }

    /**
     * 自発的な操作があったときに呼ぶ。前回の操作から猶予内なら、その間をアクティブ時間に足す。
     */
    public void touch(long now, long timeoutMillis) {
        long previous = lastActivityAt.getAndSet(now);
        long delta = now - previous;
        if (delta > 0 && delta <= timeoutMillis) {
            activeMillis.addAndGet(delta);
        }
    }

    public long activeMillis() {
        return activeMillis.get();
    }

    public long takeActiveDelta() {
        long total = activeMillis.get();
        return total - flushedActiveMillis.getAndSet(total);
    }

    /**
     * 前回のフラッシュからの実時間。日別の滞在時間はこちらで積む。
     */
    public long takeDurationDelta(long now) {
        long total = now - joinAt;
        return total - flushedDurationMillis.getAndSet(total);
    }

    public void switchWorld(String world, long now) {
        accumulateWorld(now);
        this.currentWorld = world;
        this.worldSince = now;
        this.positionKnown = false;
    }

    public void accumulateWorld(long now) {
        if (currentWorld == null) {
            return;
        }
        long delta = now - worldSince;
        if (delta > 0) {
            worldMillis.computeIfAbsent(currentWorld, key -> new AtomicLong()).addAndGet(delta);
            worldSince = now;
        }
    }

    public boolean positionKnown() {
        return positionKnown;
    }

    public void position(double x, double y, double z) {
        this.lastX = x;
        this.lastY = y;
        this.lastZ = z;
        this.positionKnown = true;
    }

    public double lastX() {
        return lastX;
    }

    public double lastY() {
        return lastY;
    }

    public double lastZ() {
        return lastZ;
    }
}
