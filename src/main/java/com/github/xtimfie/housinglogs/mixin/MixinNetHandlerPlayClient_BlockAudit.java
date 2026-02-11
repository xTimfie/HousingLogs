package com.github.xtimfie.housinglogs.mixin;

import com.github.xtimfie.housinglogs.BlockAuditManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.S22PacketMultiBlockChange;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.network.play.server.S25PacketBlockBreakAnim;
import net.minecraft.util.BlockPos;
import net.minecraft.block.state.IBlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NetHandlerPlayClient.class)
public class MixinNetHandlerPlayClient_BlockAudit {

    @Inject(method = "handleBlockBreakAnim", at = @At("HEAD"))
    private void hitlist$blockBreakAnim(S25PacketBlockBreakAnim packet, CallbackInfo ci) {
        try {
            BlockPos pos = packet.getPosition();
            int breakerId = packet.getBreakerId();
            int progress = packet.getProgress();
            BlockAuditManager.noteBlockBreakAnim(pos, breakerId, progress);
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "handleBlockChange", at = @At("HEAD"))
    private void hitlist$blockChange(S23PacketBlockChange packet, CallbackInfo ci) {
        // Logging is handled in World#setBlockState to avoid packet-handler mapping issues and to prevent duplicates.
    }

    @Inject(method = "handleMultiBlockChange", at = @At("HEAD"))
    private void hitlist$multiBlockChange(S22PacketMultiBlockChange packet, CallbackInfo ci) {
        // Logging is handled in World#setBlockState to avoid packet-handler mapping issues and to prevent duplicates.
    }
}
