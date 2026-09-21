package Extinction.gen;

import arc.util.Log;
import Extinction.core.io.PropertiesFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/**
 * Persistent Evict server tuning values.
 * Stored relative to the server working directory so values survive terminal
 * closes, full Java restarts and normal plugin updates.
 */
public final class EvictSettings {

    public enum OreKind {
        COPPER("copper", 29.94d, 0.82d, 3.10d, 0.13d),
        LEAD("lead", 27.44d, 0.83d, 3.10d, 0.16d),
        COAL("coal", 24.95d, 0.83d, 1.71d, 0.20d),
        TITANIUM("titanium", 27.44d, 0.86d, 1.98d, 0.12d),
        THORIUM("thorium", 29.94d, 0.88d, 2.20d, 0.14d),
        SCRAP("scrap", 24.95d, 0.83d, 2.34d, 0.17d);

        final String key;
        final double defaultScale;
        final double defaultThreshold;
        final double defaultOctaves;
        final double defaultFalloff;

        OreKind(
                String key,
                double defaultScale,
                double defaultThreshold,
                double defaultOctaves,
                double defaultFalloff
        ) {
            this.key = key;
            this.defaultScale = defaultScale;
            this.defaultThreshold = defaultThreshold;
            this.defaultOctaves = defaultOctaves;
            this.defaultFalloff = defaultFalloff;
        }
    }

    record OreSettings(
            double scale,
            double threshold,
            double octaves,
            double falloff
    ) {
    }

    record WaterSettings(
            double patchAttemptsPerHex,
            int normalPatchTiles,
            double largePatchChancePercent,
            int largePatchTiles
    ) {
    }

    private static final File SETTINGS_FILE =
            new File("config/evict-map-generator.properties");
    private static final double DEFAULT_UNIT_BUILD_SPEED_MULTIPLIER = 1.4d;
    private static final double MIN_UNIT_BUILD_SPEED_MULTIPLIER = 0d;
    private static final double MAX_UNIT_BUILD_SPEED_MULTIPLIER = 100d;
    private static final int DEFAULT_EXTINCTION_TERRAIN_CHANGES_PER_TICK = 120;
    private static final int MIN_EXTINCTION_TERRAIN_CHANGES_PER_TICK = 1;
    private static final int MAX_EXTINCTION_TERRAIN_CHANGES_PER_TICK = 4096;
    private static final double DEFAULT_WATER_PATCH_ATTEMPTS_PER_HEX = 1d;
    private static final int DEFAULT_WATER_NORMAL_PATCH_TILES = 3;
    private static final double DEFAULT_WATER_LARGE_PATCH_CHANCE_PERCENT =
            13.33d;
    private static final int DEFAULT_WATER_LARGE_PATCH_TILES = 8;
    private static final double MAX_WATER_PATCH_ATTEMPTS_PER_HEX = 5d;
    private static final int MIN_WATER_PATCH_TILES = 1;
    private static final int MAX_WATER_PATCH_TILES = 64;

    private double fullWallPercent = 25d;
    private double smallWallPercent = 25d;
    private double openPercent = 25d;
    private double passagePercent = 25d;
    private int extinctionTerrainChangesPerTick =
            DEFAULT_EXTINCTION_TERRAIN_CHANGES_PER_TICK;

    /**
     * Multiplier applied to unit factory build speed every round via
     * {@link EvictRules}. Vanilla PvP hosting uses 1x/2x; Evict tunes this here
     * so the value persists and is carried into every spawned duel worker.
     */
    private double unitBuildSpeedMultiplier = DEFAULT_UNIT_BUILD_SPEED_MULTIPLIER;

    /**
     * Discord webhook the hub keeps a live status message on. A blank URL means
     * the feature is off. The message id is stored alongside it so a restart
     * keeps editing the same message instead of posting a new one every time
     * the server comes up; it is cleared whenever the URL changes, because an
     * id only means anything in the channel it was created in.
     */
    private String discordWebhookUrl = "";
    private String discordMessageId = "";

    /**
     * Discord webhook the ban log is posted to. A separate channel from the
     * status message: every ban posts a new message there, and it carries the
     * account's addresses, so it belongs somewhere only staff can read.
     */
    private String discordBanLogWebhookUrl = "";

    /**
     * Discord server and role the {@code /ban} and {@code /unban} slash
     * commands answer in. A blank server id means the commands are off; a blank
     * role means only a member holding Discord's own Administrator permission
     * may use them, so they are never open to everyone by accident. The bot
     * token is not here - it is a credential and lives in the secrets file
     * (see {@code core.io.Secrets}), and this file is rewritten by the plugin.
     */
    private String discordCommandGuild = "";
    private String discordCommandRole = "";

    private static final String DISCORD_COMMAND_GUILD_KEY =
            "discord.commands.guild";
    private static final String DISCORD_COMMAND_ROLE_KEY =
            "discord.commands.role";

    /**
     * Discord chat mirror: one channel id for the hub's chat and one per
     * worker port, keyed by that port. A missing entry simply means that
     * server's chat is not mirrored. Staff-only channels - they carry
     * everything players say. The bot token is deliberately *not* here: it
     * lives in the read-only secrets file (see {@code core.io.Secrets}), because
     * this file is rewritten by the plugin and would carry a credential
     * around. Neither is copied into a worker folder (see WorkerFolder's
     * settings sync); workers never talk to Discord.
     */
    private String chatLogHubChannel = "";
    private final TreeMap<Integer, String> chatLogPortChannels = new TreeMap<>();

    private static final String CHAT_LOG_HUB_KEY = "discord.chatlog.hub.channel";
    private static final String CHAT_LOG_PORT_PREFIX = "discord.chatlog.port.";
    private static final String CHAT_LOG_PORT_SUFFIX = ".channel";

    /**
     * Set once the bans that existed before this feature have been run through
     * the cascade. Without it every restart would re-import and re-post the
     * whole back catalogue.
     */
    private boolean banBackfillDone = false;

    /**
     * Where a banned player is told to go to appeal - a Discord invite, shown
     * on every ban kick screen. Blank means no appeal line. Copied into every
     * duel worker with the rest of this file, so a match server shows the
     * same screen.
     */
    private String banAppealUrl = "";

    /**
     * Whether the hub looks up every join's address for a VPN and writes the
     * hits to the ban log. Log only - it decides nothing. Lands in worker
     * folders with the rest of this file but is read by the hub alone.
     */
    private boolean vpnScanEnabled = true;

    /**
     * Whether a first join through a VPN is locked until an admin frees it,
     * or only written down. Decided on the hub alone; the enforcement on a
     * match server follows the hub's list either way.
     */
    private boolean vpnLockEnabled = true;

    /**
     * Internal block ids players may not build (e.g. {@code router}). Stored as
     * names rather than {@code Block} objects so this class stays free of
     * Mindustry content; {@link EvictRules} resolves them at round start. Carried
     * into every spawned duel worker via the synced settings file.
     */
    private final LinkedHashSet<String> bannedBlockNames = new LinkedHashSet<>();
    private WaterSettings waterSettings =
            new WaterSettings(
                    DEFAULT_WATER_PATCH_ATTEMPTS_PER_HEX,
                    DEFAULT_WATER_NORMAL_PATCH_TILES,
                    DEFAULT_WATER_LARGE_PATCH_CHANCE_PERCENT,
                    DEFAULT_WATER_LARGE_PATCH_TILES
            );

    private final EnumMap<OreKind, OreSettings> oreSettings =
            new EnumMap<>(OreKind.class);

    public EvictSettings() {
        for (OreKind kind : OreKind.values()) {
            oreSettings.put(
                    kind,
                    new OreSettings(
                            kind.defaultScale,
                            kind.defaultThreshold,
                            kind.defaultOctaves,
                            kind.defaultFalloff
                    )
            );
        }
    }

    public void load() {
        if (!SETTINGS_FILE.exists()) {
            save();
            Log.info(
                    "[EvictMapGenerator] Created persistent settings file: @",
                    SETTINGS_FILE.getPath()
            );
            return;
        }

        Properties properties = new Properties();

        try (FileInputStream input = new FileInputStream(SETTINGS_FILE)) {
            properties.load(input);

            setWallPercentagesWithoutSaving(
                    readDouble(
                            properties,
                            "wall.fullPercent",
                            fullWallPercent
                    ),
                    readDouble(
                            properties,
                            "wall.smallPercent",
                            smallWallPercent
                    ),
                    readDouble(
                            properties,
                            "wall.openPercent",
                            openPercent
                    ),
                    readDouble(
                            properties,
                            "wall.passagePercent",
                            passagePercent
                    )
            );

            setExtinctionTerrainChangesPerTickWithoutSaving(
                    readInt(
                            properties,
                            "extinction.terrainChangesPerTick",
                            extinctionTerrainChangesPerTick
                    )
            );

            setUnitBuildSpeedMultiplierWithoutSaving(
                    readDouble(
                            properties,
                            "rules.unitBuildSpeedMultiplier",
                            unitBuildSpeedMultiplier
                    )
            );

            setWaterSettingsWithoutSaving(
                    readDouble(
                            properties,
                            "water.patchAttemptsPerHex",
                            legacyWaterPatchAttemptsPerHex(properties)
                    ),
                    readInt(
                            properties,
                            "water.normalPatchTiles",
                            waterSettings.normalPatchTiles()
                    ),
                    readDouble(
                            properties,
                            "water.largePatchChancePercent",
                            legacyLargePatchChancePercent(properties)
                    ),
                    readInt(
                            properties,
                            "water.largePatchTiles",
                            waterSettings.largePatchTiles()
                    )
            );

            for (OreKind kind : OreKind.values()) {
                OreSettings current = ore(kind);

                setOreSettingsWithoutSaving(
                        kind,
                        readDouble(
                                properties,
                                oreProperty(kind, "scale"),
                                current.scale()
                        ),
                        readDouble(
                                properties,
                                oreProperty(kind, "threshold"),
                                current.threshold()
                        ),
                        readDouble(
                                properties,
                                oreProperty(kind, "octaves"),
                                current.octaves()
                        ),
                        readDouble(
                                properties,
                                oreProperty(kind, "falloff"),
                                current.falloff()
                        )
                );
            }

            discordWebhookUrl =
                    readString(properties, "discord.webhook.url", discordWebhookUrl).trim();
            discordMessageId =
                    readString(properties, "discord.message.id", discordMessageId).trim();
            discordBanLogWebhookUrl =
                    readString(
                            properties,
                            "discord.banlog.webhook.url",
                            discordBanLogWebhookUrl
                    ).trim();
            discordCommandGuild =
                    readString(
                            properties,
                            DISCORD_COMMAND_GUILD_KEY,
                            discordCommandGuild
                    ).trim();
            discordCommandRole =
                    readString(
                            properties,
                            DISCORD_COMMAND_ROLE_KEY,
                            discordCommandRole
                    ).trim();
            readChatLogSettings(properties);
            banBackfillDone = readBoolean(
                    properties,
                    "moderation.banBackfillDone",
                    banBackfillDone
            );
            banAppealUrl = readString(
                    properties,
                    "moderation.banAppealUrl",
                    banAppealUrl
            ).trim();
            vpnScanEnabled = readBoolean(
                    properties,
                    "moderation.vpnScan",
                    vpnScanEnabled
            );
            vpnLockEnabled = readBoolean(
                    properties,
                    "moderation.vpnLock",
                    vpnLockEnabled
            );

            setBannedBlockNamesWithoutSaving(
                    splitBannedBlockNames(
                            readString(properties, "rules.bannedBlocks", "")
                    )
            );

            // Backfill newly introduced properties after plugin upgrades.
            save();

            Log.info(
                    "[EvictMapGenerator] Loaded persistent settings: coreAttrition=@; rangeAttrition=@; walls=@; water=@; extinctionTerrain=@; unitBuildSpeed=@; bannedBlocks=@; ores=@",
                    Extinction.Attrition.coreSummary(),
                    Extinction.Attrition.rangeSummary(),
                    compactWallSettings(),
                    compactWaterSettings(),
                    compactExtinctionTerrainSettings(),
                    compactUnitBuildSpeedSettings(),
                    compactBannedBlockSettings(),
                    compactOreSettings()
            );
        } catch (Exception exception) {
            Log.err(
                    "[EvictMapGenerator] Could not load persistent settings. Keeping defaults.",
                    exception
            );
        }
    }

    public void setWallPercentages(
            double fullWall,
            double smallWall,
            double open,
            double passage
    ) {
        setWallPercentagesWithoutSaving(
                fullWall,
                smallWall,
                open,
                passage
        );
        save();
    }

    void setExtinctionTerrainChangesPerTick(int amount) {
        setExtinctionTerrainChangesPerTickWithoutSaving(amount);
        save();
    }

    public void setUnitBuildSpeedMultiplier(double multiplier) {
        setUnitBuildSpeedMultiplierWithoutSaving(multiplier);
        save();
    }

    /**
     * The block ids players may not build. On a duel worker this is the hub's
     * live banned set, synced in via the settings file at spawn; on the hub it is
     * normally empty (the hub's own bans live in {@code state.rules}, untouched by
     * this plugin).
     */
    Set<String> bannedBlockNames() {
        return new LinkedHashSet<>(bannedBlockNames);
    }

    String compactBannedBlockSettings() {
        return bannedBlockNames.isEmpty()
                ? "none"
                : String.join(", ", bannedBlockNames);
    }

    public String discordWebhookUrl() {
        return discordWebhookUrl;
    }

    public String discordMessageId() {
        return discordMessageId;
    }

    public boolean discordConfigured() {
        return !discordWebhookUrl.isBlank();
    }

    /**
     * Points the status reporter at a webhook. Pass a blank URL to turn the
     * feature off; pass a blank message id to make the next update post a new
     * message rather than edit an old one.
     */
    public void setDiscordWebhook(String url, String messageId) {
        discordWebhookUrl = url == null ? "" : url.trim();
        discordMessageId = messageId == null ? "" : messageId.trim();
        save();
    }

    /** Remembers the message the reporter is editing, across restarts. */
    public void setDiscordMessageId(String messageId) {
        String cleaned = messageId == null ? "" : messageId.trim();

        if (cleaned.equals(discordMessageId)) {
            return;
        }

        discordMessageId = cleaned;
        save();
    }

    public String discordBanLogWebhookUrl() {
        return discordBanLogWebhookUrl;
    }

    /** Points the ban log at a webhook; a blank URL turns it off. */
    public void setDiscordBanLogWebhook(String url) {
        discordBanLogWebhookUrl = url == null ? "" : url.trim();
        save();
    }

    /** Discord server the /ban and /unban commands run in; blank means off. */
    public String discordCommandGuild() {
        return discordCommandGuild;
    }

    /** Role allowed to use them; blank falls back to Discord's Administrator. */
    public String discordCommandRole() {
        return discordCommandRole;
    }

    /** Wires the moderation commands to a Discord server and role. */
    public void setDiscordCommands(String guildId, String roleId) {
        discordCommandGuild = guildId == null ? "" : guildId.trim();
        discordCommandRole = roleId == null ? "" : roleId.trim();
        save();
    }

    /** The chat mirror's hub channel id; blank when the hub is not mirrored. */
    public String chatLogHubChannel() {
        return chatLogHubChannel;
    }

    /** The chat mirror's per-port channel ids, sorted by port. */
    public Map<Integer, String> chatLogPortChannels() {
        return new TreeMap<>(chatLogPortChannels);
    }

    /** Points the chat mirror's hub feed at a channel; blank turns it off. */
    public void setChatLogHubChannel(String channelId) {
        chatLogHubChannel = channelId == null ? "" : channelId.trim();
        save();
    }

    /** Points one port's chat mirror at a channel; blank drops the entry. */
    public void setChatLogPortChannel(int port, String channelId) {
        String trimmed = channelId == null ? "" : channelId.trim();

        if (trimmed.isEmpty()) {
            chatLogPortChannels.remove(port);
        } else {
            chatLogPortChannels.put(port, trimmed);
        }

        save();
    }

    /**
     * Drops the whole chat-mirror wiring at once. The token file is left
     * alone - deleting a credential is the admin's call, not a side effect
     * of switching the mirror off.
     */
    public void clearChatLog() {
        chatLogHubChannel = "";
        chatLogPortChannels.clear();
        save();
    }

    /**
     * Reads the chat-mirror wiring. The ports are dynamic keys
     * ({@code discord.chatlog.port.<port>.channel}), so they are found by
     * prefix rather than by a fixed name like every other property.
     */
    private void readChatLogSettings(Properties properties) {
        chatLogHubChannel =
                readString(properties, CHAT_LOG_HUB_KEY, chatLogHubChannel);
        chatLogPortChannels.clear();

        for (String key : properties.stringPropertyNames()) {
            if (
                    !key.startsWith(CHAT_LOG_PORT_PREFIX)
                            || !key.endsWith(CHAT_LOG_PORT_SUFFIX)
            ) {
                continue;
            }

            String portText = key.substring(
                    CHAT_LOG_PORT_PREFIX.length(),
                    key.length() - CHAT_LOG_PORT_SUFFIX.length()
            );

            try {
                int port = Integer.parseInt(portText.trim());
                String url = properties.getProperty(key, "").trim();

                if (!url.isEmpty()) {
                    chatLogPortChannels.put(port, url);
                }
            } catch (NumberFormatException ignored) {
                // A hand-edited key that is not a port; leave it alone.
            }
        }
    }

    /**
     * True once the bans that predate the cascade have been imported. Checked
     * on hub startup so the import runs exactly once, ever.
     */
    public boolean banBackfillDone() {
        return banBackfillDone;
    }


    /** The Discord invite shown on ban screens; blank when none is set. */
    public String banAppealUrl() {
        return banAppealUrl;
    }

    public void setBanAppealUrl(String url) {
        String trimmed = url == null ? "" : url.trim();

        if (trimmed.equals(banAppealUrl)) {
            return;
        }

        banAppealUrl = trimmed;
        save();
    }

    /** True while the hub writes VPN joins to the ban log. */
    public boolean vpnScanEnabled() {
        return vpnScanEnabled;
    }

    public void setVpnScanEnabled(boolean enabled) {
        if (vpnScanEnabled == enabled) {
            return;
        }

        vpnScanEnabled = enabled;
        save();
    }

    /** True while a first join through a VPN is locked rather than only logged. */
    public boolean vpnLockEnabled() {
        return vpnLockEnabled;
    }

    public void setVpnLockEnabled(boolean enabled) {
        if (vpnLockEnabled == enabled) {
            return;
        }

        vpnLockEnabled = enabled;
        save();
    }

    public void markBanBackfillDone() {
        if (banBackfillDone) {
            return;
        }

        banBackfillDone = true;
        save();
    }

    public void setWaterSettings(
            double patchAttemptsPerHex,
            int normalPatchTiles,
            double largePatchChancePercent,
            int largePatchTiles
    ) {
        setWaterSettingsWithoutSaving(
                patchAttemptsPerHex,
                normalPatchTiles,
                largePatchChancePercent,
                largePatchTiles
        );
        save();
    }

    public void setOreSettings(
            OreKind kind,
            double scale,
            double threshold,
            double octaves,
            double falloff
    ) {
        setOreSettingsWithoutSaving(
                kind,
                scale,
                threshold,
                octaves,
                falloff
        );
        save();
    }

    OreSettings ore(OreKind kind) {
        return oreSettings.get(kind);
    }

    WaterSettings water() {
        return waterSettings;
    }

    double fullWallChance() {
        return fullWallPercent / 100d;
    }

    double smallWallChance() {
        return smallWallPercent / 100d;
    }

    double openChance() {
        return openPercent / 100d;
    }

    double passageChance() {
        return passagePercent / 100d;
    }

    int extinctionTerrainChangesPerTick() {
        return extinctionTerrainChangesPerTick;
    }

    double unitBuildSpeedMultiplier() {
        return unitBuildSpeedMultiplier;
    }

    public String compactWallSettings() {
        return "full-wall=" + formatPercent(fullWallPercent)
                + "%, small-wall=" + formatPercent(smallWallPercent)
                + "%, open=" + formatPercent(openPercent)
                + "%, passage=" + formatPercent(passagePercent) + "%";
    }

    String compactExtinctionTerrainSettings() {
        return Integer.toString(extinctionTerrainChangesPerTick);
    }

    public String compactUnitBuildSpeedSettings() {
        return formatNumber(unitBuildSpeedMultiplier) + "x";
    }

    public String compactWaterSettings() {
        return "tries-per-hex="
                + formatNumber(waterSettings.patchAttemptsPerHex())
                + ", normal="
                + waterSettings.normalPatchTiles()
                + " tiles, large="
                + formatPercent(waterSettings.largePatchChancePercent())
                + "% at "
                + waterSettings.largePatchTiles()
                + " tiles";
    }

    public String compactOreSettings() {
        StringBuilder result = new StringBuilder();

        for (OreKind kind : OreKind.values()) {
            if (!result.isEmpty()) {
                result.append("; ");
            }

            result.append(compactOreSettings(kind));
        }

        return result.toString();
    }

    public String compactOreSettings(OreKind kind) {
        OreSettings ore = ore(kind);

        return kind.key
                + "(scale=" + formatNumber(ore.scale())
                + ", threshold=" + formatNumber(ore.threshold())
                + ", octaves=" + formatNumber(ore.octaves())
                + ", falloff=" + formatNumber(ore.falloff())
                + ")";
    }

    private void setWallPercentagesWithoutSaving(
            double possiblyInvalidFullWall,
            double possiblyInvalidSmallWall,
            double possiblyInvalidOpen,
            double possiblyInvalidPassage
    ) {
        double fullWall = validatePercentage("full-wall", possiblyInvalidFullWall);
        double smallWall = validatePercentage("small-wall", possiblyInvalidSmallWall);
        double open = validatePercentage("open", possiblyInvalidOpen);
        double passage = validatePercentage("passage", possiblyInvalidPassage);

        double sum = fullWall + smallWall + open + passage;

        if (Math.abs(sum - 100d) > 0.0001d) {
            throw new IllegalArgumentException(
                    "Wall percentages must add up to exactly 100."
            );
        }

        fullWallPercent = fullWall;
        smallWallPercent = smallWall;
        openPercent = open;
        passagePercent = passage;
    }

    private void setExtinctionTerrainChangesPerTickWithoutSaving(int amount) {
        if (
                amount < MIN_EXTINCTION_TERRAIN_CHANGES_PER_TICK
                        || amount > MAX_EXTINCTION_TERRAIN_CHANGES_PER_TICK
        ) {
            throw new IllegalArgumentException(
                    "Extinction terrain changes per tick must be between "
                            + MIN_EXTINCTION_TERRAIN_CHANGES_PER_TICK
                            + " and "
                            + MAX_EXTINCTION_TERRAIN_CHANGES_PER_TICK
                            + "."
            );
        }

        extinctionTerrainChangesPerTick = amount;
    }

    private void setUnitBuildSpeedMultiplierWithoutSaving(double multiplier) {
        unitBuildSpeedMultiplier = validateRange(
                "Unit build speed multiplier",
                multiplier,
                MIN_UNIT_BUILD_SPEED_MULTIPLIER,
                MAX_UNIT_BUILD_SPEED_MULTIPLIER
        );
    }

    private void setWaterSettingsWithoutSaving(
            double possiblyInvalidPatchAttemptsPerHex,
            int possiblyInvalidNormalPatchTiles,
            double possiblyInvalidLargePatchChancePercent,
            int possiblyInvalidLargePatchTiles
    ) {
        double patchAttemptsPerHex = validateRange(
                "Water patch tries per hex",
                possiblyInvalidPatchAttemptsPerHex,
                0d,
                MAX_WATER_PATCH_ATTEMPTS_PER_HEX
        );
        int normalPatchTiles = validateIntRange(
                "Water normal patch tiles",
                possiblyInvalidNormalPatchTiles,
                MIN_WATER_PATCH_TILES,
                MAX_WATER_PATCH_TILES
        );
        double largePatchChancePercent = validatePercentage(
                "Water large patch chance",
                possiblyInvalidLargePatchChancePercent
        );
        int largePatchTiles = validateIntRange(
                "Water large patch tiles",
                possiblyInvalidLargePatchTiles,
                MIN_WATER_PATCH_TILES,
                MAX_WATER_PATCH_TILES
        );

        waterSettings = new WaterSettings(
                patchAttemptsPerHex,
                normalPatchTiles,
                largePatchChancePercent,
                largePatchTiles
        );
    }

    private void setOreSettingsWithoutSaving(
            OreKind kind,
            double possiblyInvalidScale,
            double possiblyInvalidThreshold,
            double possiblyInvalidOctaves,
            double possiblyInvalidFalloff
    ) {
        if (kind == null) {
            throw new IllegalArgumentException("Ore kind is required.");
        }

        double scale = validatePositiveFinite(kind.key + " scale", possiblyInvalidScale);
        double threshold = validateRange(kind.key + " threshold", possiblyInvalidThreshold, 0d, 1d);
        double octaves = validatePositiveFinite(kind.key + " octaves", possiblyInvalidOctaves);
        double falloff = validateRange(kind.key + " falloff", possiblyInvalidFalloff, 0d, 1d);

        oreSettings.put(
                kind,
                new OreSettings(scale, threshold, octaves, falloff)
        );
    }

    private void setBannedBlockNamesWithoutSaving(Collection<String> names) {
        bannedBlockNames.clear();

        if (names == null) {
            return;
        }

        for (String name : names) {
            String normalized = normalizeBlockName(name);

            if (!normalized.isEmpty()) {
                bannedBlockNames.add(normalized);
            }
        }
    }

    private LinkedHashSet<String> splitBannedBlockNames(String packed) {
        LinkedHashSet<String> names = new LinkedHashSet<>();

        if (packed == null || packed.isBlank()) {
            return names;
        }

        for (String entry : packed.split(",")) {
            String normalized = normalizeBlockName(entry);

            if (!normalized.isEmpty()) {
                names.add(normalized);
            }
        }

        return names;
    }

    private String normalizeBlockName(String name) {
        return name == null ? "" : name.trim();
    }

    private double validatePositiveFinite(String name, double value) {
        if (
                Double.isNaN(value)
                        || Double.isInfinite(value)
                        || value <= 0d
        ) {
            throw new IllegalArgumentException(name + " must be greater than 0.");
        }

        return value;
    }

    private double validateRange(
            String name,
            double value,
            double minimum,
            double maximum
    ) {
        if (
                Double.isNaN(value)
                        || Double.isInfinite(value)
                        || value < minimum
                        || value > maximum
        ) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum + "."
            );
        }

        return value;
    }

    private int validateIntRange(
            String name,
            int value,
            int minimum,
            int maximum
    ) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum + "."
            );
        }

        return value;
    }

    private double validatePercentage(String name, double value) {
        if (
                Double.isNaN(value)
                        || Double.isInfinite(value)
                        || value < 0d
                        || value > 100d
        ) {
            throw new IllegalArgumentException(
                    name + " must be between 0 and 100."
            );
        }

        return value;
    }

    private double readDouble(
            Properties properties,
            String key,
            double fallback
    ) {
        String value = properties.getProperty(key);

        if (value == null || value.isBlank()) {
            return fallback;
        }

        return Double.parseDouble(value.trim());
    }

    private int readInt(
            Properties properties,
            String key,
            int fallback
    ) {
        String value = properties.getProperty(key);

        if (value == null || value.isBlank()) {
            return fallback;
        }

        return Integer.parseInt(value.trim());
    }

    private boolean readBoolean(
            Properties properties,
            String key,
            boolean fallback
    ) {
        String value = properties.getProperty(key);

        if (value == null || value.isBlank()) {
            return fallback;
        }

        return Boolean.parseBoolean(value.trim());
    }

    private String readString(
            Properties properties,
            String key,
            String fallback
    ) {
        String value = properties.getProperty(key);

        return value == null ? fallback : value.trim();
    }

    private double legacyWaterPatchAttemptsPerHex(Properties properties) {
        String legacyPercent = properties.getProperty(
                "water.patchAttemptsPercentPerHex"
        );

        if (legacyPercent == null || legacyPercent.isBlank()) {
            legacyPercent = properties.getProperty("water.patchAttemptsPercent");
        }

        if (legacyPercent == null || legacyPercent.isBlank()) {
            return waterSettings.patchAttemptsPerHex();
        }

        return Double.parseDouble(legacyPercent.trim()) / 100d;
    }

    private double legacyLargePatchChancePercent(Properties properties) {
        String largeValue = properties.getProperty("water.largePatchWeight");

        if (largeValue == null || largeValue.isBlank()) {
            return waterSettings.largePatchChancePercent();
        }

        double smallWeight = readDouble(
                properties,
                "water.smallPatchWeight",
                0d
        );
        double mediumWeight = readDouble(
                properties,
                "water.mediumPatchWeight",
                0d
        );
        double largeWeight = Double.parseDouble(largeValue.trim());
        double totalWeight = smallWeight + mediumWeight + largeWeight;

        if (totalWeight <= 0d) {
            return waterSettings.largePatchChancePercent();
        }

        return largeWeight * 100d / totalWeight;
    }

    private void save() {
        File parent = SETTINGS_FILE.getParentFile();

        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.err(
                    "[EvictMapGenerator] Could not create settings directory: @",
                    parent.getPath()
            );
            return;
        }

        // Starts from the file so keys owned by Config (or unknown here) are never dropped.
        Properties properties = PropertiesFile.load(SETTINGS_FILE);
        properties.setProperty(
                "wall.fullPercent",
                Double.toString(fullWallPercent)
        );
        properties.setProperty(
                "wall.smallPercent",
                Double.toString(smallWallPercent)
        );
        properties.setProperty(
                "wall.openPercent",
                Double.toString(openPercent)
        );
        properties.setProperty(
                "wall.passagePercent",
                Double.toString(passagePercent)
        );
        properties.setProperty(
                "extinction.terrainChangesPerTick",
                Integer.toString(extinctionTerrainChangesPerTick)
        );
        properties.setProperty(
                "rules.unitBuildSpeedMultiplier",
                Double.toString(unitBuildSpeedMultiplier)
        );
        properties.setProperty("discord.webhook.url", discordWebhookUrl);
        properties.setProperty("discord.message.id", discordMessageId);
        properties.setProperty(
                "discord.banlog.webhook.url",
                discordBanLogWebhookUrl
        );
        properties.setProperty(DISCORD_COMMAND_GUILD_KEY, discordCommandGuild);
        properties.setProperty(DISCORD_COMMAND_ROLE_KEY, discordCommandRole);
        properties.setProperty(CHAT_LOG_HUB_KEY, chatLogHubChannel);

        for (Map.Entry<Integer, String> entry : chatLogPortChannels.entrySet()) {
            properties.setProperty(
                    CHAT_LOG_PORT_PREFIX + entry.getKey() + CHAT_LOG_PORT_SUFFIX,
                    entry.getValue()
            );
        }

        properties.setProperty(
                "moderation.banBackfillDone",
                Boolean.toString(banBackfillDone)
        );
        properties.setProperty("moderation.banAppealUrl", banAppealUrl);
        properties.setProperty(
                "moderation.vpnScan",
                Boolean.toString(vpnScanEnabled)
        );
        properties.setProperty(
                "moderation.vpnLock",
                Boolean.toString(vpnLockEnabled)
        );
        properties.setProperty(
                "rules.bannedBlocks",
                String.join(",", bannedBlockNames)
        );
        properties.setProperty(
                "water.patchAttemptsPerHex",
                Double.toString(waterSettings.patchAttemptsPerHex())
        );
        properties.setProperty(
                "water.normalPatchTiles",
                Integer.toString(waterSettings.normalPatchTiles())
        );
        properties.setProperty(
                "water.largePatchChancePercent",
                Double.toString(waterSettings.largePatchChancePercent())
        );
        properties.setProperty(
                "water.largePatchTiles",
                Integer.toString(waterSettings.largePatchTiles())
        );

        for (OreKind kind : OreKind.values()) {
            OreSettings ore = ore(kind);

            properties.setProperty(
                    oreProperty(kind, "scale"),
                    Double.toString(ore.scale())
            );
            properties.setProperty(
                    oreProperty(kind, "threshold"),
                    Double.toString(ore.threshold())
            );
            properties.setProperty(
                    oreProperty(kind, "octaves"),
                    Double.toString(ore.octaves())
            );
            properties.setProperty(
                    oreProperty(kind, "falloff"),
                    Double.toString(ore.falloff())
            );
        }

        try (FileOutputStream output = new FileOutputStream(SETTINGS_FILE)) {
            properties.store(output, "EvictMapGenerator persistent settings");
        } catch (IOException exception) {
            Log.err(
                    "[EvictMapGenerator] Could not save persistent settings.",
                    exception
            );
        }
    }

    private String oreProperty(OreKind kind, String field) {
        return "ore." + kind.key + "." + field;
    }

    private String formatPercent(double value) {
        return formatNumber(value);
    }

    private String formatNumber(double value) {
        if (Math.rint(value) == value) {
            return Long.toString(Math.round(value));
        }

        return Double.toString(value);
    }
}
