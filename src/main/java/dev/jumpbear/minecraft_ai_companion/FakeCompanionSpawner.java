package dev.jumpbear.minecraft_ai_companion;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.util.math.Vec3d;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

public final class FakeCompanionSpawner {
    private static final String COMPANION_NAME = "AICompanion";

    private FakeCompanionSpawner() {
    }

    public static ServerPlayerEntity spawnNear(ServerCommandSource source) {
        MinecraftServer server = source.getServer();
        ServerWorld world = source.getWorld();
        Vec3d pos = source.getPosition();
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(("minecraft-ai-companion:" + COMPANION_NAME).getBytes(StandardCharsets.UTF_8)), COMPANION_NAME);
        ConnectedClientData clientData = ConnectedClientData.createDefault(profile, false);

        ServerPlayerEntity player = new ServerPlayerEntity(server, world, profile, clientData.syncedOptions());
        player.refreshPositionAndAngles(pos.x + 1.0D, pos.y, pos.z + 1.0D, source.getRotation().y, source.getRotation().x);

        ClientConnection connection = new LocalClientConnection(NetworkSide.SERVERBOUND);
        server.getPlayerManager().onPlayerConnect(connection, player, clientData);
        player.teleport(world, pos.x + 1.0D, pos.y, pos.z + 1.0D, Set.<PositionFlag>of(), source.getRotation().y, source.getRotation().x, true);
        return player;
    }
}
