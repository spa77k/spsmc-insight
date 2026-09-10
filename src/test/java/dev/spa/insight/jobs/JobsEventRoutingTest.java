package dev.spa.insight.jobs;

import org.bukkit.Server;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

class JobsEventRoutingTest {
    // Reproduce Jobs' shared handler list: sibling events reach the same executor.
    public static class SharedEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }
    public static final class PrePayment extends SharedEvent {}
    public static final class ExperienceGain extends SharedEvent {}
    public static final class ChunkChange extends SharedEvent {}

    @Test void sharedHandlersDoNotSendSiblingEventsToPaymentObserver() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        Logger logger = mock(Logger.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(logger);
        when(server.getPluginManager()).thenReturn(manager);
        JobsAudit audit = new JobsAudit(plugin, null, null, null);
        @SuppressWarnings("unchecked") Consumer<Event> observer = mock(Consumer.class);
        var bind = JobsAudit.class.getDeclaredMethod("bind", org.bukkit.plugin.Plugin.class,
                String.class, EventPriority.class, Consumer.class);
        bind.setAccessible(true);
        for (EventPriority priority : new EventPriority[]{EventPriority.LOWEST, EventPriority.MONITOR}) {
            bind.invoke(audit, plugin, PrePayment.class.getName(), priority, observer);
            var executor = ArgumentCaptor.forClass(EventExecutor.class);
            verify(manager).registerEvent(eq(PrePayment.class), same(audit), eq(priority),
                    executor.capture(), same(plugin), eq(false));
            PrePayment payment = new PrePayment();
            ExperienceGain experience = new ExperienceGain();
            assertSame(payment.getHandlers(), experience.getHandlers());
            executor.getValue().execute(audit, experience);
            executor.getValue().execute(audit, new ChunkChange());
            verifyNoInteractions(observer, logger);
            executor.getValue().execute(audit, payment);
            verify(observer).accept(payment);
            verifyNoMoreInteractions(observer);
            clearInvocations(observer);
        }
    }
}
