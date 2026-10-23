package dev.spa.insight.listener;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class QuickShopListenerTest {
    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    /** QuickShop の QUser と同じ名前のメソッドだけを持つ代役。 */
    public static class FakeUser {
        private final UUID uuid;
        private final boolean real;

        FakeUser(UUID uuid, boolean real) {
            this.uuid = uuid;
            this.real = real;
        }

        public Optional<UUID> getUniqueIdIfRealPlayer() {
            return real ? Optional.of(uuid) : Optional.empty();
        }

        public boolean isRealPlayer() {
            return real;
        }

        public UUID getUniqueId() {
            return uuid;
        }
    }

    public enum ShopType { SELLING }

    public static class FakeShop {
        public ShopType getShopType() {
            return ShopType.SELLING;
        }
    }

    @Test
    void readsRealPlayerUuid() {
        assertEquals(PLAYER, QuickShopListener.uuidOf(new FakeUser(PLAYER, true)));
    }

    @Test
    void ignoresNonPlayerUsers() {
        assertNull(QuickShopListener.uuidOf(new FakeUser(PLAYER, false)));
        assertNull(QuickShopListener.uuidOf(null));
    }

    @Test
    void readsShopTypeOrUnknown() {
        assertEquals("SELLING", QuickShopListener.shopType(new FakeShop()));
        assertEquals("UNKNOWN", QuickShopListener.shopType(null));
    }
}
