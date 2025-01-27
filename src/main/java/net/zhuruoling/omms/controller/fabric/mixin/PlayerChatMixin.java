package net.zhuruoling.omms.controller.fabric.mixin;


import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.zhuruoling.omms.controller.fabric.config.Config;
import net.zhuruoling.omms.controller.fabric.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//owe great thanks to Gugle and his SuperEvent mod
@Mixin(value = net.minecraft.server.network.ServerPlayNetworkHandler.class)
public class PlayerChatMixin {
    @Shadow
    public ServerPlayerEntity player;

    @Inject(at = @At("RETURN"), method = "onChatMessage")
    private void handleMessage(ChatMessageC2SPacket packet, CallbackInfo ci) {
        if (!Config.INSTANCE.isEnableChatBridge()) return;
        String raw = packet.chatMessage();
        //System.out.println(raw);
        if (!raw.startsWith("/")) {
            Util.sendChatBroadcast(raw, this.player.getName().getString());
        }
    }
}
