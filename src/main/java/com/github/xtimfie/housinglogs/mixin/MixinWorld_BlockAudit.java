package com.github.xtimfie.housinglogs.mixin;

import com.github.xtimfie.housinglogs.BlockAuditManager;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
public class MixinWorld_BlockAudit {

    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void hitlist$setBlockState(BlockPos pos, IBlockState newState, int flags, CallbackInfoReturnable<Boolean> cir) {
        try {
            Object self = this;
            if (!(self instanceof WorldClient)) return;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null) return;
            if (mc.theWorld != self) return;

            World world = (World) self;
            IBlockState oldState = world.getBlockState(pos);
            // Allow heuristic here; the area constraint keeps this cheap.
            BlockAuditManager.noteBlockChange(pos, oldState, newState, true);
        } catch (Throwable ignored) {
        }
    }
}
