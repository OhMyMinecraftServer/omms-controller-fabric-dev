package net.zhuruoling.omms.controller.fabric.network;


import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;


public class UdpReceiver extends Thread{
    private static final Logger logger = LogManager.getLogger("UdpBroadcastReceiver");
    BiConsumer<MinecraftServer, String> function = null;
    UdpBroadcastSender.Target target = null;
    private final MinecraftServer server;
    public UdpReceiver(MinecraftServer server, UdpBroadcastSender.Target target, BiConsumer<MinecraftServer, String> function){
        this.setName("UdpBroadcastReceiver#" + getId());
        this.server = server;
        this.function = function;
        this.target = target;
    }

    @Override
    public void run() {
        try {
            int port = target.port();
            String address = target.address(); // 224.114.51.4:10086
            MulticastSocket socket;
            InetAddress inetAddress;
            inetAddress = InetAddress.getByName(address);
            socket = new MulticastSocket(port);
            logger.info("Started Broadcast Receiver at " + address + ":" + port);
            socket.joinGroup(new InetSocketAddress(inetAddress,port), NetworkInterface.getByInetAddress(inetAddress));
            for (;;) {
                try {
                    DatagramPacket packet = new DatagramPacket(new byte[1024], 1024);
                    socket.receive(packet);
                    String msg = new String(packet.getData(), packet.getOffset(),
                            packet.getLength(), StandardCharsets.UTF_8);
                    function.accept(this.server,msg);
                }
                catch (Exception e){
                    e.printStackTrace();
                }
            }
        }
        catch (Exception e){
            e.printStackTrace();
        }
    }
}
