package dev.spa.insight.model;

/**
 * セッション単位で数えるカウンタの名前。文字列を散らかさないためここに集める。
 */
public final class Metrics {

    public static final String BLOCKS_BROKEN = "blocks_broken";
    public static final String BLOCKS_PLACED = "blocks_placed";
    public static final String ORES_MINED = "ores_mined";
    public static final String ITEMS_CRAFTED = "items_crafted";
    public static final String ITEMS_PICKED_UP = "items_picked_up";
    public static final String ITEMS_DROPPED = "items_dropped";
    public static final String ITEMS_CONSUMED = "items_consumed";
    public static final String CHAT_MESSAGES = "chat_messages";
    public static final String CHAT_CHARACTERS = "chat_characters";
    public static final String COMMANDS_USED = "commands_used";
    public static final String DEATHS = "deaths";
    public static final String RESPAWNS = "respawns";
    public static final String KILLS_PLAYER = "kills_player";
    public static final String KILLS_MOB = "kills_mob";
    public static final String DAMAGE_TAKEN = "damage_taken";
    public static final String DAMAGE_DEALT = "damage_dealt";
    public static final String INVENTORY_CLICKS = "inventory_clicks";
    public static final String CONTAINERS_OPENED = "containers_opened";
    public static final String INTERACTIONS = "interactions";
    public static final String ADVANCEMENTS = "advancements";
    public static final String WORLD_CHANGES = "world_changes";
    public static final String TELEPORTS = "teleports";
    public static final String PORTAL_USES = "portal_uses";
    public static final String BEDS_ENTERED = "beds_entered";
    public static final String FISH_CAUGHT = "fish_caught";
    public static final String ANIMALS_TAMED = "animals_tamed";
    public static final String ANIMALS_BRED = "animals_bred";
    public static final String VILLAGER_TRADES = "villager_trades";
    public static final String BOOKS_EDITED = "books_edited";
    public static final String DISTANCE_CM = "distance_cm";
    public static final String DISTANCE_VEHICLE_CM = "distance_vehicle_cm";
    public static final String DISTANCE_FLIGHT_CM = "distance_flight_cm";

    public static final String ACTION_BREAK = "break";
    public static final String ACTION_PLACE = "place";
    public static final String ACTION_CRAFT = "craft";
    public static final String ACTION_DEATH_CAUSE = "death_cause";
    public static final String ACTION_COMMAND = "command";

    private Metrics() {
    }
}
