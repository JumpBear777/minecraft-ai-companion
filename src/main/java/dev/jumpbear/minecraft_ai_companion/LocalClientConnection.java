package dev.jumpbear.minecraft_ai_companion;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.listener.TickablePacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.state.NetworkState;
import net.minecraft.text.Text;

import java.util.function.Consumer;

public final class LocalClientConnection extends ClientConnection {
    private PacketListener packetListener;
    private DisconnectionInfo disconnectionInfo;

    public LocalClientConnection(NetworkSide side) {
        super(side);
    }

    @Override
    public void send(Packet<?> packet) {
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener) {
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
    }

    @Override
    public void submit(Consumer<ClientConnection> task) {
        task.accept(this);
    }

    @Override
    public void flush() {
    }

    @Override
    public void tick() {
        if (packetListener instanceof TickablePacketListener tickablePacketListener) {
            tickablePacketListener.tick();
        }
    }

    @Override
    public <T extends PacketListener> void transitionInbound(NetworkState<T> state, T listener) {
        this.packetListener = listener;
    }

    @Override
    public void transitionOutbound(NetworkState<?> state) {
    }

    @Override
    public void setInitialPacketListener(PacketListener listener) {
        this.packetListener = listener;
    }

    @Override
    public PacketListener getPacketListener() {
        return packetListener;
    }

    @Override
    public void disconnect(Text disconnectReason) {
        this.disconnectionInfo = new DisconnectionInfo(disconnectReason);
    }

    @Override
    public void disconnect(DisconnectionInfo disconnectionInfo) {
        this.disconnectionInfo = disconnectionInfo;
    }

    @Override
    public boolean isOpen() {
        return true;
    }

    @Override
    public boolean isChannelAbsent() {
        return false;
    }

    @Override
    public DisconnectionInfo getDisconnectionInfo() {
        return disconnectionInfo;
    }
}
