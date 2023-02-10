package net.zhuruoling.omms.controller.fabric;

import com.google.gson.Gson;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.logging.LogQueues;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.command.EntitySelector;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import net.minecraft.world.explosion.Explosion;
import net.zhuruoling.omms.controller.fabric.command.AnnouncementCommand;
import net.zhuruoling.omms.controller.fabric.command.MenuCommand;
import net.zhuruoling.omms.controller.fabric.command.QQCommand;
import net.zhuruoling.omms.controller.fabric.command.SendToConsoleCommand;
import net.zhuruoling.omms.controller.fabric.config.Config;
import net.zhuruoling.omms.controller.fabric.config.SharedVariable;
import net.zhuruoling.omms.controller.fabric.network.Broadcast;
import net.zhuruoling.omms.controller.fabric.network.Instruction;
import net.zhuruoling.omms.controller.fabric.network.UdpBroadcastSender;
import net.zhuruoling.omms.controller.fabric.network.UdpReceiver;
import net.zhuruoling.omms.controller.fabric.network.http.HttpServerMainKt;
import net.zhuruoling.omms.controller.fabric.util.OmmsCommandOutput;
import net.zhuruoling.omms.controller.fabric.util.Util;
import net.zhuruoling.omms.controller.fabric.util.logging.LogUpdateThread;

import java.util.Objects;

public class OmmsControllerFabric implements DedicatedServerModInitializer {

    //private final Logger logger = LogUtils.getLogger();

    private static void onServerStop(MinecraftServer minecraftServer) {
        if (Config.INSTANCE.isEnableRemoteControl() || Config.INSTANCE.isEnableChatBridge())
            SharedVariable.getSender().setStopped(true);
        if (Config.INSTANCE.isEnableChatBridge())
            SharedVariable.getChatReceiver().interrupt();
        if (Config.INSTANCE.isEnableRemoteControl()) {
            HttpServerMainKt.httpServer.stop(1000, 1000);
            HttpServerMainKt.httpServerThread.interrupt();
            SharedVariable.logUpdateThread.interrupt();
        }
        SharedVariable.getExecutorService().shutdown();
    }

    private static void registerMenuCommand() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> new MenuCommand().register(dispatcher));
    }

    @Override
    public void onInitializeServer() {
        Config.INSTANCE.load();

        if (Config.INSTANCE.isEnableJoinMotd()) {
            registerMenuCommand();
        }

        if (Config.INSTANCE.isEnableRemoteControl()) {
            CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> new SendToConsoleCommand().register(dispatcher));
            SharedVariable.logUpdateThread = new LogUpdateThread();
            SharedVariable.logUpdateThread.start();
        }

        CommandRegistrationCallback.EVENT.register(((dispatcher, registryAccess, environment) -> new AnnouncementCommand().register(dispatcher)));

        if (Config.INSTANCE.isEnableChatBridge()) {
            CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> new QQCommand().register(dispatcher));
        }

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(LiteralArgumentBuilder.<ServerCommandSource>literal("crashNow").requires(serverCommandSource -> serverCommandSource.hasPermissionLevel(4)).executes(context -> {
                SharedVariable.shouldCrash = (true);
                return 0;
            }));
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(LiteralArgumentBuilder.<ServerCommandSource>literal("omms-reload").requires(serverCommandSource -> serverCommandSource.hasPermissionLevel(4)).executes(context -> {
                Config.INSTANCE.load();
                HttpServerMainKt.httpServer.stop(1, 1);
                HttpServerMainKt.httpServerThread.interrupt();
                HttpServerMainKt.httpServerThread = HttpServerMainKt.serverMain(Config.INSTANCE.getHttpServerPort(), context.getSource().getServer());
                context.getSource().sendFeedback(Text.of("Config reloaded.").copyContentOnly().setStyle(Style.EMPTY.withColor(TextColor.fromFormatting(Formatting.AQUA))), true);
                return 0;
            }));
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(LiteralArgumentBuilder.<ServerCommandSource>literal("boom")
                .requires(serverCommandSource -> serverCommandSource.hasPermissionLevel(4))
                .then(RequiredArgumentBuilder.<ServerCommandSource, EntitySelector>argument("entities", EntityArgumentType.entities())
                        .then(RequiredArgumentBuilder.<ServerCommandSource, Float>argument("power", FloatArgumentType.floatArg(0)).executes(context -> {
                                    var world = context.getSource().getWorld();
                                    var entities = EntityArgumentType.getEntities(context, "entities");
                                    entities.forEach(e -> world.createExplosion(e,
                                            DamageSource.explosion((LivingEntity) e),
                                            null,
                                            e.getX(),
                                            e.getY(),
                                            e.getZ(),
                                            FloatArgumentType.getFloat(context, "power"),
                                            false,
                                            Explosion.DestructionType.BREAK
                                    ));
                                    return 0;
                                })
                        ))));
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStart);
        ServerLifecycleEvents.SERVER_STOPPING.register(OmmsControllerFabric::onServerStop);
        ServerLifecycleEvents.SERVER_STOPPED.register(OmmsControllerFabric::onServerStop);
        SharedVariable.ready = true;

        LogUtils.getLogger().info("Hello World!");
    }

    private void onServerStart(MinecraftServer server) {
        if (Config.INSTANCE.isEnableChatBridge()) {
            var chatReceiver = new UdpReceiver(server, Util.TARGET_CHAT, (s, m) -> {
                var broadcast = new Gson().fromJson(m, Broadcast.class);
                if (!(Objects.equals(broadcast.getChannel(), Config.INSTANCE.getChatChannel()))) return;
                //logger.info(String.format("%s <%s[%s]> %s", Objects.requireNonNull(broadcast).getChannel(), broadcast.getPlayer(), broadcast.getServer(), broadcast.getContent()));
                if (broadcast.getPlayer().startsWith("\ufff3\ufff4")) {
                    server.execute(() -> server.getPlayerManager().broadcast(Util.fromBroadcastToQQ(broadcast), false));
                    return;
                }
                if (!Objects.equals(broadcast.getServer(), Config.INSTANCE.getControllerName())) {
                    server.execute(() -> server.getPlayerManager().broadcast(Util.fromBroadcast(broadcast), false));
                }
            });

            chatReceiver.setDaemon(true);
            chatReceiver.start();
            SharedVariable.setChatReceiver(chatReceiver);
        }

        if (Config.INSTANCE.isEnableRemoteControl()) {
            HttpServerMainKt.httpServerThread = HttpServerMainKt.serverMain(Config.INSTANCE.getHttpServerPort(), server);
        }
        if (Config.INSTANCE.isEnableRemoteControl() || Config.INSTANCE.isEnableChatBridge()) {
            var sender = new UdpBroadcastSender();
            sender.setDaemon(true);
            sender.start();
            SharedVariable.setSender(sender);
        }
    }


}
