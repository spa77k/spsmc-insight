package dev.spa.insight.milestone;

import java.util.List;

/**
 * 初回到達を記録する項目。ファネル（どの段階で落ちたか）の軸になる。
 * 並び順はおおむね想定する体験の順で、エクスポートでもこの順に出す。
 */
public final class Milestones {

    public static final String FIRST_JOIN = "first_join";
    public static final String FIRST_CHAT = "first_chat";
    public static final String FIRST_COMMAND = "first_command";
    public static final String FIRST_INTERACT = "first_interact";
    public static final String FIRST_BLOCK_BREAK = "first_block_break";
    public static final String FIRST_BLOCK_PLACE = "first_block_place";
    public static final String FIRST_CRAFT = "first_craft";
    public static final String FIRST_ORE = "first_ore_mined";
    public static final String FIRST_CONTAINER = "first_container_opened";
    public static final String FIRST_DEATH = "first_death";
    public static final String FIRST_ADVANCEMENT = "first_advancement";
    public static final String FIRST_BED = "first_bed";
    public static final String FIRST_MOB_KILL = "first_mob_kill";
    public static final String FIRST_PVP_KILL = "first_pvp_kill";
    public static final String FIRST_FISH = "first_fish";
    public static final String FIRST_TAME = "first_tame";
    public static final String FIRST_VILLAGER_TRADE = "first_villager_trade";
    public static final String FIRST_WORLD_CHANGE = "first_world_change";
    public static final String FIRST_NETHER = "first_nether";
    public static final String FIRST_END = "first_end";
    public static final String FIRST_BUILD_WORLD = "first_build_world";
    public static final String FIRST_RESOURCE_WORLD = "first_resource_world";
    public static final String SECOND_SESSION = "second_session";
    public static final String FIFTH_SESSION = "fifth_session";
    public static final String TENTH_SESSION = "tenth_session";
    public static final String RETURNED_NEXT_DAY = "returned_next_day";
    public static final String PLAYTIME_1H = "playtime_1h";
    public static final String PLAYTIME_10H = "playtime_10h";
    public static final String PLAYTIME_50H = "playtime_50h";

    public static final String FIRST_CLAIM = "first_land_claim";
    public static final String FIRST_LOCK = "first_container_lock";
    public static final String FIRST_SHOP_CREATE = "first_shop_created";
    public static final String FIRST_SHOP_TRADE = "first_shop_trade";
    public static final String FIRST_JOB = "first_job_joined";
    public static final String FIRST_CONTRACT_POST = "first_contract_posted";
    public static final String FIRST_CONTRACT_ACCEPT = "first_contract_accepted";
    public static final String FIRST_DISCORD_LINK = "first_discord_link";
    public static final String LEVEL_1 = "reached_level_1";
    public static final String LEVEL_2 = "reached_level_2";
    public static final String LEVEL_3 = "reached_level_3";

    /**
     * ファネルとして並べたときの順番。エクスポートの funnel 節はこの順で出す。
     */
    public static final List<String> FUNNEL_ORDER = List.of(
            FIRST_JOIN,
            FIRST_INTERACT,
            FIRST_BLOCK_BREAK,
            FIRST_BLOCK_PLACE,
            FIRST_CRAFT,
            FIRST_CHAT,
            FIRST_ADVANCEMENT,
            FIRST_ORE,
            FIRST_DEATH,
            FIRST_WORLD_CHANGE,
            FIRST_RESOURCE_WORLD,
            FIRST_BUILD_WORLD,
            FIRST_DISCORD_LINK,
            LEVEL_1,
            FIRST_CLAIM,
            FIRST_JOB,
            FIRST_SHOP_CREATE,
            FIRST_SHOP_TRADE,
            FIRST_CONTRACT_POST,
            FIRST_CONTRACT_ACCEPT,
            SECOND_SESSION,
            RETURNED_NEXT_DAY,
            PLAYTIME_1H,
            FIFTH_SESSION,
            LEVEL_2,
            TENTH_SESSION,
            PLAYTIME_10H,
            LEVEL_3,
            PLAYTIME_50H
    );

    private Milestones() {
    }
}
