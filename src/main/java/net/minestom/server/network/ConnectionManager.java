package net.minestom.server.network;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerFlag;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.AsyncPlayerPreLoginEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.listener.preplay.LoginListener;
import net.minestom.server.network.packet.server.CachedPacket;
import net.minestom.server.network.packet.server.common.KeepAlivePacket;
import net.minestom.server.network.packet.server.common.PluginMessagePacket;
import net.minestom.server.network.packet.server.common.TagsPacket;
import net.minestom.server.network.packet.server.configuration.FinishConfigurationPacket;
import net.minestom.server.network.packet.server.configuration.ResetChatPacket;
import net.minestom.server.network.packet.server.configuration.SelectKnownPacksPacket;
import net.minestom.server.network.packet.server.configuration.UpdateEnabledFeaturesPacket;
import net.minestom.server.network.packet.server.login.LoginSuccessPacket;
import net.minestom.server.network.packet.server.play.StartConfigurationPacket;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerConnection;
import net.minestom.server.network.player.PlayerSocketConnection;
import net.minestom.server.network.plugin.LoginPluginMessageProcessor;
import net.minestom.server.registry.Registries;
import net.minestom.server.registry.StaticProtocolObject;
import net.minestom.server.utils.StringUtils;
import net.minestom.server.utils.validate.Check;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MpscUnboundedArrayQueue;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Manages the connected clients.
 */
public final class ConnectionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionManager.class);

    private static final Component TIMEOUT_TEXT = Component.text("Timeout", NamedTextColor.RED);
    private static final Component SHUTDOWN_TEXT = Component.text("Server shutting down");

    private final CachedPacket cachedTagsPacket = new CachedPacket(this::createTagsPacket);

    // All players once their Player object has been instantiated.
    private final Map<PlayerConnection, Player> connectionPlayerMap = new ConcurrentHashMap<>();
    // Players waiting to be spawned (post configuration state)
    private final MessagePassingQueue<Player> waitingPlayers = new MpscUnboundedArrayQueue<>(64);
    // Players in configuration state
    private final Set<Player> configurationPlayers = new CopyOnWriteArraySet<>();
    // Players in play state
    private final Set<Player> playPlayers = new CopyOnWriteArraySet<>();

    // The players who need keep alive ticks. This was added because we may not send a keep alive in
    // the time after sending finish configuration but before receiving configuration end (to swap to play).
    // I(mattw) could not come up with a better way to express this besides completely splitting client/server
    // states. Perhaps there will be an improvement in the future.
    private final Set<Player> keepAlivePlayers = new CopyOnWriteArraySet<>();

    private final Set<Player> unmodifiableConfigurationPlayers = Collections.unmodifiableSet(configurationPlayers);
    private final Set<Player> unmodifiablePlayPlayers = Collections.unmodifiableSet(playPlayers);

    // The player provider to have your own Player implementation
    private volatile PlayerProvider playerProvider = Player::new;

    /**
     * Gets the number of "online" players, eg for the query response.
     *
     * <p>Only includes players in the play state, not players in configuration.</p>
     */
    public int getOnlinePlayerCount() {
        return playPlayers.size();
    }

    /**
     * Returns an unmodifiable set containing the players currently in the play state.
     */
    public @NotNull Collection<@NotNull Player> getOnlinePlayers() {
        return unmodifiablePlayPlayers;
    }

    /**
     * Returns an unmodifiable set containing the players currently in the configuration state.
     */
    public @NotNull Collection<@NotNull Player> getConfigPlayers() {
        return unmodifiableConfigurationPlayers;
    }

    /**
     * Gets the {@link Player} linked to a {@link PlayerConnection}.
     *
     * <p>The player will be returned whether they are in the play or config state,
     * so be sure to check before sending packets to them.</p>
     *
     * @param connection the player connection
     * @return the player linked to the connection
     */
    public Player getPlayer(@NotNull PlayerConnection connection) {
        return connectionPlayerMap.get(connection);
    }

    /**
     * Gets the first player in the play state which validates {@link String#equalsIgnoreCase(String)}.
     * <p>
     * This can cause issue if two or more players have the same username.
     *
     * @param username the player username (case-insensitive)
     * @return the first player who validate the username condition, null if none was found
     */
    public @Nullable Player getOnlinePlayerByUsername(@NotNull String username) {
        for (Player player : getOnlinePlayers()) {
            if (player.getUsername().equalsIgnoreCase(username))
                return player;
        }
        return null;
    }

    /**
     * Gets the first player in the play state which validates {@link UUID#equals(Object)}.
     * <p>
     * This can cause issue if two or more players have the same UUID.
     *
     * @param uuid the player UUID
     * @return the first player who validate the UUID condition, null if none was found
     */
    public @Nullable Player getOnlinePlayerByUuid(@NotNull UUID uuid) {
        for (Player player : getOnlinePlayers()) {
            if (player.getUuid().equals(uuid))
                return player;
        }
        return null;
    }

    /**
     * Finds the closest player in the play state matching a given username.
     *
     * @param username the player username (can be partial)
     * @return the closest match, null if no players are online
     */
    public @Nullable Player findOnlinePlayer(@NotNull String username) {
        Player exact = getOnlinePlayerByUsername(username);
        if (exact != null) return exact;
        final String username1 = username.toLowerCase(Locale.ROOT);

        Function<Player, Double> distanceFunction = player -> {
            final String username2 = player.getUsername().toLowerCase(Locale.ROOT);
            return StringUtils.jaroWinklerScore(username1, username2);
        };
        return getOnlinePlayers().stream()
                .min(Comparator.comparingDouble(distanceFunction::apply))
                .filter(player -> distanceFunction.apply(player) > 0)
                .orElse(null);
    }

    /**
     * Changes the {@link Player} provider, to change which object to link to him.
     *
     * @param playerProvider the new {@link PlayerProvider}, can be set to null to apply the default provider
     */
    public void setPlayerProvider(@Nullable PlayerProvider playerProvider) {
        this.playerProvider = playerProvider != null ? playerProvider : Player::new;
    }

    @ApiStatus.Internal
    public @NotNull Player createPlayer(@NotNull PlayerConnection connection, @NotNull GameProfile gameProfile) {
        assert ServerFlag.INSIDE_TEST || Thread.currentThread().isVirtual();
        final Player player = playerProvider.createPlayer(connection, gameProfile);
        this.connectionPlayerMap.put(connection, player);
        return player;
    }

    public void sendRegistryTags(@NotNull Player player) {
        player.sendPacket(cachedTagsPacket);
    }

    // This is a somewhat weird implementation where connectionmanager owns the caching of tags.
    // There should be no registry->connectionmanager communication.
    @ApiStatus.Internal
    public void invalidateTags() {
        this.cachedTagsPacket.invalidate();
    }

    public GameProfile transitionLoginToConfig(@NotNull PlayerConnection connection, @NotNull GameProfile gameProfile) {
        assert ServerFlag.INSIDE_TEST || Thread.currentThread().isVirtual();
        // Compression
        if (connection instanceof PlayerSocketConnection socketConnection) {
            final int threshold = MinecraftServer.getCompressionThreshold();
            if (threshold > 0) socketConnection.startCompression();
        }
        // Call pre login event
        LoginPluginMessageProcessor pluginMessageProcessor = connection.loginPluginMessageProcessor();
        AsyncPlayerPreLoginEvent asyncPlayerPreLoginEvent = new AsyncPlayerPreLoginEvent(connection, gameProfile, pluginMessageProcessor);
        EventDispatcher.call(asyncPlayerPreLoginEvent);
        if (!connection.isOnline()) return gameProfile; // Player has been kicked
        // Change UUID/Username based on the event
        gameProfile = asyncPlayerPreLoginEvent.getGameProfile();
        // Wait for pending login plugin messages
        try {
            pluginMessageProcessor.awaitReplies(ServerFlag.LOGIN_PLUGIN_MESSAGE_TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            connection.kick(LoginListener.INVALID_PROXY_RESPONSE);
            throw new RuntimeException("Error getting replies for login plugin messages", t);
        }
        // Send login success packet (and switch to configuration phase)
        connection.sendPacket(new LoginSuccessPacket(gameProfile));
        return gameProfile;
    }

    @ApiStatus.Internal
    public void transitionPlayToConfig(@NotNull Player player) {
        player.sendPacket(new StartConfigurationPacket());
        configurationPlayers.add(player);
    }

    /**
     * Return value exposed for testing
     */
    @ApiStatus.Internal
    public void doConfiguration(@NotNull Player player, boolean isFirstConfig) {
        assert ServerFlag.INSIDE_TEST || Thread.currentThread().isVirtual();
        if (isFirstConfig) {
            configurationPlayers.add(player);
            keepAlivePlayers.add(player);
        }
        player.sendPacket(PluginMessagePacket.brandPacket(MinecraftServer.getBrandName()));
        // Request known packs immediately, but don't wait for the response until required (sending registry data).
        final var knownPacksFuture = player.getPlayerConnection().requestKnownPacks(List.of(SelectKnownPacksPacket.MINECRAFT_CORE));

        var event = new AsyncPlayerConfigurationEvent(player, isFirstConfig);
        EventDispatcher.call(event);
        if (!player.isOnline()) return; // Player was kicked during config.

        // send player features that were enabled or disabled during async config event
        player.sendPacket(new UpdateEnabledFeaturesPacket(event.getFeatureFlags().stream().map(StaticProtocolObject::name).toList()));

        final Instance spawningInstance = event.getSpawningInstance();
        Check.notNull(spawningInstance, "You need to specify a spawning instance in the AsyncPlayerConfigurationEvent");

        if (event.willClearChat()) player.sendPacket(new ResetChatPacket());

        // Registry data (if it should be sent)
        if (event.willSendRegistryData()) {
            List<SelectKnownPacksPacket.Entry> knownPacks;
            try {
                knownPacks = knownPacksFuture.get(ServerFlag.KNOWN_PACKS_RESPONSE_TIMEOUT, TimeUnit.MILLISECONDS);
            } catch (InterruptedException | TimeoutException e) {
                LOGGER.warn("Player {} failed to respond to known packs query", player.getUsername());
                player.getPlayerConnection().disconnect();
                return;
            } catch (ExecutionException e) {
                throw new RuntimeException("Error receiving known packs", e);
            }
            boolean excludeVanilla = knownPacks.contains(SelectKnownPacksPacket.MINECRAFT_CORE);

            Registries registries = MinecraftServer.process();
            player.sendPacket(registries.chatType().registryDataPacket(registries, excludeVanilla));
            player.sendPacket(registries.dimensionType().registryDataPacket(registries, excludeVanilla));
            player.sendPacket(registries.biome().registryDataPacket(registries, excludeVanilla));
            player.sendPacket(registries.dialog().registryDataPacket(registries, excludeVanilla));
            player.sendPacket(registries.damageType().registryDataPacket(registries, excludeVanilla));
            player.sendPacket(registries.trimMaterial().registryDataPacket(registries, excludeVanilla));
            player.sendPacket(registries.trimPattern().registryDataPacket(registries, excludeVanilla));
            player.sendPacket(registries.bannerPattern().registryDataPacket(registries, excludeVanilla));
            player.sendPacket(registries.enchantment().registryDataPacket(registries, excludeVanilla));
            player.sendPacket(registries.paintingVariant().registryDataPacket(registries, excludeVanilla));
            player.sendPacket(registries.jukeboxSong().registryDataPacket(registries, excludeVanilla));
            player.sendPacket(registries.instrument().registryDataPacket(registries, excludeVanilla));
            player.sendPacket(registries.wolfVariant().registryDataPacket(registries, excludeVanilla));
            player.sendPacket(registries.wolfSoundVariant().registryDataPacket(registries, excludeVanilla));
            player.sendPacket(registries.catVariant().registryDataPacket(registries, excludeVanilla));
            player.sendPacket(registries.chickenVariant().registryDataPacket(registries, excludeVanilla));
            player.sendPacket(registries.cowVariant().registryDataPacket(registries, excludeVanilla));
            player.sendPacket(registries.frogVariant().registryDataPacket(registries, excludeVanilla));
            player.sendPacket(registries.pigVariant().registryDataPacket(registries, excludeVanilla));

            sendRegistryTags(player);
        }

        // Wait for pending resource packs if any
        var packFuture = player.getResourcePackFuture();
        if (packFuture != null) packFuture.join();

        keepAlivePlayers.remove(player);
        player.setPendingOptions(spawningInstance, event.isHardcore());
        player.sendPacket(new FinishConfigurationPacket());
    }

    @ApiStatus.Internal
    public void transitionConfigToPlay(@NotNull Player player) {
        this.waitingPlayers.relaxedOffer(player);
    }

    /**
     * Removes a {@link Player} from the players list.
     * <p>
     * Used during disconnection, you shouldn't have to do it manually.
     *
     * @param connection the player connection
     * @see PlayerConnection#disconnect() to properly disconnect a player
     */
    @ApiStatus.Internal
    public synchronized void removePlayer(@NotNull PlayerConnection connection) {
        final Player player = this.connectionPlayerMap.remove(connection);
        if (player == null) return;
        this.configurationPlayers.remove(player);
        this.playPlayers.remove(player);
        this.keepAlivePlayers.remove(player);
    }

    /**
     * Shutdowns the connection manager by kicking all the currently connected players.
     */
    public synchronized void shutdown() {
        for (final PlayerConnection configPlayer : connectionPlayerMap.keySet())
            configPlayer.kick(SHUTDOWN_TEXT);
        this.configurationPlayers.clear();
        for (final Player playPlayer : playPlayers)
            playPlayer.kick(SHUTDOWN_TEXT);
        this.playPlayers.clear();

        this.keepAlivePlayers.clear();
        this.connectionPlayerMap.clear();
    }

    public void tick(long tickStart) {
        // Let waiting players into their instances
        updateWaitingPlayers();

        // Send keep alive packets
        handleKeepAlive(keepAlivePlayers, tickStart);

        // Interpret packets for configuration players
        configurationPlayers.forEach(Player::interpretPacketQueue);
    }

    /**
     * Connects waiting players.
     */
    @ApiStatus.Internal
    public void updateWaitingPlayers() {
        this.waitingPlayers.drain(player -> {
            if (!player.isOnline()) return; // Player disconnected while in queued to join
            configurationPlayers.remove(player);
            playPlayers.add(player);
            keepAlivePlayers.add(player);

            // This fixes a bug with Geyser. They do not reply to keep alive during config, meaning that
            // `Player#didAnswerKeepAlive()` will always be false when entering the play state, so a new keep
            // alive will never be sent and they will disconnect themselves or we will kick them for not replying.
            player.refreshAnswerKeepAlive(true);

            // Spawn the player at Player#getRespawnPoint
            CompletableFuture<Void> spawnFuture = player.UNSAFE_init();

            // Required to get the exact moment the player spawns
            if (ServerFlag.INSIDE_TEST) spawnFuture.join();
        });
    }

    /**
     * Updates keep alive by checking the last keep alive packet and send a new one if needed.
     *
     * @param tickStart the time of the update in nanoseconds, forwarded to the packet
     */
    private void handleKeepAlive(@NotNull Collection<Player> playerGroup, long tickStart) {
        final KeepAlivePacket keepAlivePacket = new KeepAlivePacket(tickStart);
        for (Player player : playerGroup) {
            final long lastKeepAlive = tickStart - player.getLastKeepAlive();
            if (lastKeepAlive > TimeUnit.MILLISECONDS.toNanos(ServerFlag.KEEP_ALIVE_DELAY) && player.didAnswerKeepAlive()) {
                player.refreshKeepAlive(tickStart);
                player.sendPacket(keepAlivePacket);
            } else if (lastKeepAlive >= TimeUnit.MILLISECONDS.toNanos(ServerFlag.KEEP_ALIVE_KICK)) {
                player.kick(TIMEOUT_TEXT);
            }
        }
    }

    private @NotNull TagsPacket createTagsPacket() {
        final List<TagsPacket.Registry> entries = new ArrayList<>();

        // The following are the registries which contain tags used by the vanilla client.
        // We don't care about registries unused by the client.
        final Registries registries = MinecraftServer.process();
        entries.add(registries.bannerPattern().tagRegistry());
        entries.add(registries.biome().tagRegistry());
        entries.add(registries.blocks().tagRegistry());
        entries.add(registries.catVariant().tagRegistry());
        entries.add(registries.damageType().tagRegistry());
        entries.add(registries.dialog().tagRegistry());
        entries.add(registries.enchantment().tagRegistry());
        entries.add(registries.entityType().tagRegistry());
        entries.add(registries.fluid().tagRegistry());
        entries.add(registries.gameEvent().tagRegistry());
        entries.add(registries.instrument().tagRegistry());
        entries.add(registries.material().tagRegistry());
        entries.add(registries.paintingVariant().tagRegistry());

        return new TagsPacket(entries);
    }
}
