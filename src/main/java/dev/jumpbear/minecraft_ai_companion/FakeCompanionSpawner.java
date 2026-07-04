package dev.jumpbear.minecraft_ai_companion;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class FakeCompanionSpawner {
    public static final String COMPANION_NAME = "AICompanion";
    private static final UUID COMPANION_UUID = UUID.nameUUIDFromBytes(("minecraft-ai-companion:" + COMPANION_NAME).getBytes(StandardCharsets.UTF_8));
    private static LocalClientConnection companionConnection;

    private FakeCompanionSpawner() {
    }

    public static ServerPlayerEntity spawnNear(ServerCommandSource source) {
        Optional<ServerPlayerEntity> existing = find(source.getServer());
        if (existing.isPresent()) {
            existing.get().changeGameMode(GameMode.SURVIVAL);
            teleportNear(existing.get(), source);
            return existing.get();
        }

        MinecraftServer server = source.getServer();
        ServerWorld world = source.getWorld();
        Vec3d pos = source.getPosition();
        GameProfile profile = new GameProfile(COMPANION_UUID, COMPANION_NAME);
        ConnectedClientData clientData = ConnectedClientData.createDefault(profile, false);

        ServerPlayerEntity player = new ServerPlayerEntity(server, world, profile, clientData.syncedOptions());
        player.refreshPositionAndAngles(pos.x + 1.0D, pos.y, pos.z + 1.0D, source.getRotation().y, source.getRotation().x);

        LocalClientConnection connection = new LocalClientConnection(NetworkSide.SERVERBOUND);
        companionConnection = connection;
        server.getNetworkIo().getConnections().add(connection);
        server.getPlayerManager().onPlayerConnect(connection, player, clientData);
        player.changeGameMode(GameMode.SURVIVAL);
        player.teleport(world, pos.x + 1.0D, pos.y, pos.z + 1.0D, Set.<PositionFlag>of(), source.getRotation().y, source.getRotation().x, true);
        return player;
    }

    public static Optional<ServerPlayerEntity> find(MinecraftServer server) {
        return Optional.ofNullable(server.getPlayerManager().getPlayer(COMPANION_UUID));
    }

    public static boolean isCompanion(ServerPlayerEntity player) {
        return COMPANION_UUID.equals(player.getUuid());
    }

    public static void teleportNear(ServerPlayerEntity companion, ServerCommandSource source) {
        Vec3d pos = source.getPosition();
        companion.teleport(source.getWorld(), pos.x + 1.0D, pos.y, pos.z + 1.0D, Set.<PositionFlag>of(), source.getRotation().y, source.getRotation().x, true);
    }

    public static boolean remove(MinecraftServer server) {
        Optional<ServerPlayerEntity> companion = find(server);
        if (companion.isEmpty()) {
            return false;
        }

        ServerPlayerEntity player = companion.get();
        DisconnectionInfo disconnectionInfo = new DisconnectionInfo(Text.literal("AI companion prototype removed"));
        if (companionConnection != null) {
            companionConnection.disconnect(disconnectionInfo);
            companionConnection = null;
        }
        player.networkHandler.onDisconnected(disconnectionInfo);
        return true;
    }
}
