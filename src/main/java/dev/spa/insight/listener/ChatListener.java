package dev.spa.insight.listener;

import dev.spa.insight.milestone.Milestones;
import dev.spa.insight.model.LiveSession;
import dev.spa.insight.model.Metrics;
import dev.spa.insight.tracking.SessionTracker;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;

/**
 * チャットとコマンド。文面は保存せず、件数と文字数、コマンド名だけを残す。
 */
public class ChatListener implements Listener {

    private final SessionTracker tracker;

    public ChatListener(SessionTracker tracker) {
        this.tracker = tracker;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        LiveSession session = tracker.session(player);
        if (session == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String plain = PlainTextComponentSerializer.plainText().serialize(event.message());
        session.touch(now, Long.MAX_VALUE);
        session.increment(Metrics.CHAT_MESSAGES);
        session.add(Metrics.CHAT_CHARACTERS, plain.length());
        tracker.milestones().record(player.getUniqueId(), Milestones.FIRST_CHAT, now, "live");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        LiveSession session = tracker.session(player);
        if (session == null) {
            return;
        }
        long now = System.currentTimeMillis();
        session.touch(now, Long.MAX_VALUE);
        session.increment(Metrics.COMMANDS_USED);
        session.addBreakdown(Metrics.ACTION_COMMAND, commandName(event.getMessage()), 1L);
        tracker.milestones().record(player.getUniqueId(), Milestones.FIRST_COMMAND, now, "live");
    }

    /**
     * 引数は残さない。コマンド名だけを内訳の鍵にする。
     */
    private static String commandName(String message) {
        String text = message.startsWith("/") ? message.substring(1) : message;
        int space = text.indexOf(' ');
        String name = space < 0 ? text : text.substring(0, space);
        return name.toLowerCase(Locale.ROOT);
    }
}
