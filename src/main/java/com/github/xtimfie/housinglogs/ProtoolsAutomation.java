package com.github.xtimfie.housinglogs;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Client-side automation for Hypixel Housing Pro Tools workflows.
 *
 * Adds a client command that can be invoked as "//setblock ..." (command name is "/setblock"
 * after the client strips the first '/').
 */
public final class ProtoolsAutomation {
    public static final ProtoolsAutomation INSTANCE = new ProtoolsAutomation();

    // Cooldowns between chat commands.
    // Hypixel may rate-limit command usage; these delays help avoid "Slow down".
    // Keep this very small so //pos happens almost instantly after a tp.
    private static final int TP_TO_POS_DELAY_TICKS = 1;
    private static final int PROTOOLS_STEP_DELAY_TICKS = 10;
    private static final int DESELECT_DELAY_TICKS = 14;
    private static final int RETURN_TO_DESELECT_DELAY_TICKS = 14;
    private static final int TP_TIMEOUT_TICKS = 120;

    // Retry settings if //pos is executed at the wrong block due to server nudges.
    private static final int POS_RETRY_COUNT = 3;
    private static final int TP_RETRY_DELAY_TICKS = 12;

    // After we send //pos, wait for a Pro Tools confirmation chat message.
    // If none arrives, fall back to checking the player's block position at send time.
    private static final int POS_CONFIRM_TIMEOUT_TICKS = 40;

    // How close we need to be to consider the teleport "good enough" to attempt //pos.
    private static final double TP_NEAR_TOLERANCE_XZ = 0.65;
    private static final double TP_NEAR_TOLERANCE_Y = 1.10;

    private Job job;

    private ProtoolsAutomation() {
    }

    public static void init() {
        ClientCommandHandler.instance.registerCommand(new CommandProtoSetBlock());
        ClientCommandHandler.instance.registerCommand(new CommandProtoFillBlocks());
        MinecraftForge.EVENT_BUS.register(INSTANCE);
    }

    private enum Operation {
        SET,
        FILL
    }

    private static final class Job {
        final BlockPos pos1Target;
        final BlockPos pos2Target;
        final String blockId;
        final Operation operation;

        final double returnX;
        final double returnY;
        final double returnZ;
        final float returnYaw;
        final float returnPitch;

        int step = 0;
        int waitTicks = 0;
        int tpWaitTicks = 0;

        PendingConfirm pendingConfirm = PendingConfirm.NONE;
        int confirmTicksRemaining = 0;
        boolean confirmOk = false;
        boolean confirmBad = false;

        BlockPos playerBlockAtPosSend = null;

        int retriesRemainingPos1 = POS_RETRY_COUNT;
        int retriesRemainingPos2 = POS_RETRY_COUNT;

        Job(BlockPos pos1Target, BlockPos pos2Target, String blockId, Operation operation,
            double returnX, double returnY, double returnZ, float returnYaw, float returnPitch) {
            this.pos1Target = pos1Target;
            this.pos2Target = pos2Target;
            this.blockId = blockId;
            this.operation = operation;
            this.returnX = returnX;
            this.returnY = returnY;
            this.returnZ = returnZ;
            this.returnYaw = returnYaw;
            this.returnPitch = returnPitch;
        }
    }

    private enum PendingConfirm {
        NONE,
        POS1,
        POS2
    }

    private static final class CommandProtoSetBlock extends CommandBase {
        @Override
        public String getCommandName() {
            // Allows typing "//setblock" in chat.
            // The client strips the first '/', leaving "/setblock" as the command name.
            return "/setblock";
        }

        @Override
        public String getCommandUsage(ICommandSender sender) {
            return "//setblock <x> <y> <z> <blockID>";
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 0;
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            if (args.length != 4) {
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[ProtoolsAutomation] Usage: " + getCommandUsage(sender)));
                return;
            }

            int x;
            int y;
            int z;
            try {
                x = Integer.parseInt(args[0]);
                y = Integer.parseInt(args[1]);
                z = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[ProtoolsAutomation] Coordinates must be integers."));
                return;
            }

            String blockId = args[3];
            if (blockId == null || blockId.trim().isEmpty()) {
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[ProtoolsAutomation] <blockID> cannot be empty."));
                return;
            }

            if (!HousingContext.isInHousing()) {
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[ProtoolsAutomation] Not in Housing (HOUSING scoreboard not detected)."));
                return;
            }

            boolean started = INSTANCE.startSetBlockJob(sender, new BlockPos(x, y, z), blockId.trim());
            if (!started) {
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[ProtoolsAutomation] An automation job is already running."));
            }
        }
    }

    private static final class CommandProtoFillBlocks extends CommandBase {
        @Override
        public String getCommandName() {
            // Allows typing "//fillblocks" in chat.
            // The client strips the first '/', leaving "/fillblocks" as the command name.
            return "/fillblocks";
        }

        @Override
        public String getCommandUsage(ICommandSender sender) {
            return "//fillblocks <x1> <y1> <z1> <x2> <y2> <z2> <blockID>";
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 0;
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            if (args.length != 7) {
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[ProtoolsAutomation] Usage: " + getCommandUsage(sender)));
                return;
            }

            int x1;
            int y1;
            int z1;
            int x2;
            int y2;
            int z2;
            try {
                x1 = Integer.parseInt(args[0]);
                y1 = Integer.parseInt(args[1]);
                z1 = Integer.parseInt(args[2]);
                x2 = Integer.parseInt(args[3]);
                y2 = Integer.parseInt(args[4]);
                z2 = Integer.parseInt(args[5]);
            } catch (NumberFormatException e) {
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[ProtoolsAutomation] Coordinates must be integers."));
                return;
            }

            String blockId = args[6];
            if (blockId == null || blockId.trim().isEmpty()) {
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[ProtoolsAutomation] <blockID> cannot be empty."));
                return;
            }

            if (!HousingContext.isInHousing()) {
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[ProtoolsAutomation] Not in Housing (HOUSING scoreboard not detected)."));
                return;
            }

            boolean started = INSTANCE.startFillBlocksJob(sender,
                    new BlockPos(x1, y1, z1),
                    new BlockPos(x2, y2, z2),
                    blockId.trim());
            if (!started) {
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[ProtoolsAutomation] An automation job is already running."));
            }
        }
    }

    private boolean startSetBlockJob(ICommandSender sender, BlockPos target, String blockId) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            if (sender != null) {
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[ProtoolsAutomation] Player not available."));
            }
            return false;
        }

        if (job != null) return false;

        job = new Job(
            target,
            target,
            blockId,
            Operation.SET,
                mc.thePlayer.posX,
                mc.thePlayer.posY,
                mc.thePlayer.posZ,
                mc.thePlayer.rotationYaw,
                mc.thePlayer.rotationPitch
        );

        if (sender != null) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[ProtoolsAutomation] Starting //setblock automation..."));
        }

        // Kick the state machine on the next tick.
        return true;
    }

    private boolean startFillBlocksJob(ICommandSender sender, BlockPos pos1, BlockPos pos2, String blockId) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            if (sender != null) {
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[ProtoolsAutomation] Player not available."));
            }
            return false;
        }

        if (job != null) return false;

        job = new Job(
                pos1,
                pos2,
                blockId,
                Operation.FILL,
                mc.thePlayer.posX,
                mc.thePlayer.posY,
                mc.thePlayer.posZ,
                mc.thePlayer.rotationYaw,
                mc.thePlayer.rotationPitch
        );

        if (sender != null) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[ProtoolsAutomation] Starting //fillblocks automation..."));
        }
        return true;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (job == null) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            job = null;
            return;
        }

        // Prevent accidental movement during the automation.
        // This is client-side only (server can still nudge you), but it helps keep
        // your position stable for the //pos commands.
        freezeMovementInputs(mc);
        mc.thePlayer.motionX = 0.0;
        mc.thePlayer.motionY = 0.0;
        mc.thePlayer.motionZ = 0.0;

        if (job.waitTicks > 0) {
            job.waitTicks--;
            return;
        }

        try {
            advance(job, mc);
        } catch (Throwable t) {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "[ProtoolsAutomation] Automation failed: " + t.getClass().getSimpleName()));
            job = null;
        }
    }

    @SubscribeEvent
    public void onChatReceived(ClientChatReceivedEvent event) {
        if (job == null) return;
        if (event == null || event.message == null) return;

        String msg;
        try {
            msg = event.message.getUnformattedText();
        } catch (Throwable t) {
            return;
        }
        if (msg == null || msg.isEmpty()) return;

        PendingConfirm pending = job.pendingConfirm;
        if (pending == PendingConfirm.NONE) return;

        String lower = msg.toLowerCase(java.util.Locale.ROOT);
        boolean isPos1 = pending == PendingConfirm.POS1 && (lower.contains("pos1") || lower.contains("first position") || lower.contains("position 1"));
        boolean isPos2 = pending == PendingConfirm.POS2 && (lower.contains("pos2") || lower.contains("second position") || lower.contains("position 2"));
        if (!isPos1 && !isPos2) return;

        BlockPos coords = parseCoordsFromMessage(msg);
        if (coords == null) return;

        BlockPos expected = (pending == PendingConfirm.POS1) ? job.pos1Target : job.pos2Target;

        if (expected != null && coords.getX() == expected.getX() && coords.getY() == expected.getY() && coords.getZ() == expected.getZ()) {
            job.confirmOk = true;
        } else {
            job.confirmBad = true;
        }
    }

    private void advance(Job j, Minecraft mc) {
        switch (j.step) {
            case 0: {
                // TP to target (for pos1)
                sendTpToBlockCenter(mc, j.pos1Target);
                j.step = 1;
                j.tpWaitTicks = 0;
                // Start checking immediately; do not add extra buffer before //pos.
                j.waitTicks = 0;
                break;
            }

            case 1: {
                // Wait until we're near the target (or timeout), then attempt //pos1.
                j.tpWaitTicks++;

                if (isNearTarget(mc, j.pos1Target) || j.tpWaitTicks >= TP_TIMEOUT_TICKS) {
                    j.step = 2;
                    j.waitTicks = TP_TO_POS_DELAY_TICKS;
                }
                break;
            }

            case 2: {
                send(mc, "//pos1");
                mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[ProtoolsAutomation] Executed //pos1"));
                beginConfirm(j, PendingConfirm.POS1, mc);
                j.step = 20;
                j.waitTicks = 0;
                break;
            }

            case 20: {
                // Wait for confirmation of pos1
                if (tickConfirm(j, mc)) {
                    // success
                    mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[ProtoolsAutomation] //pos1 confirmed at target"));
                    j.step = 3;
                    j.waitTicks = PROTOOLS_STEP_DELAY_TICKS;
                } else if (j.confirmBad || j.confirmTicksRemaining <= 0) {
                    // failure -> retry
                    if (j.retriesRemainingPos1 > 0) {
                        j.retriesRemainingPos1--;
                        mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[ProtoolsAutomation] Retrying pos1 (" + (j.retriesRemainingPos1 + 1) + " left)..."));
                        resetConfirm(j);
                        j.step = 0;
                        j.waitTicks = TP_RETRY_DELAY_TICKS;
                    } else {
                        fail(mc, "//pos1 could not be set at the target block after retries.");
                    }
                }
                break;
            }

            case 3: {
                // TP to target (for pos2)
                sendTpToBlockCenter(mc, j.pos2Target);
                j.step = 4;
                j.tpWaitTicks = 0;
                // Start checking immediately; do not add extra buffer before //pos.
                j.waitTicks = 0;
                break;
            }

            case 4: {
                // Wait until we're near the target (or timeout), then attempt //pos2.
                j.tpWaitTicks++;

                if (isNearTarget(mc, j.pos2Target) || j.tpWaitTicks >= TP_TIMEOUT_TICKS) {
                    j.step = 5;
                    j.waitTicks = TP_TO_POS_DELAY_TICKS;
                }
                break;
            }

            case 5: {
                send(mc, "//pos2");
                mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[ProtoolsAutomation] Executed //pos2"));
                beginConfirm(j, PendingConfirm.POS2, mc);
                j.step = 21;
                j.waitTicks = 0;
                break;
            }

            case 21: {
                // Wait for confirmation of pos2
                if (tickConfirm(j, mc)) {
                    mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[ProtoolsAutomation] //pos2 confirmed at target"));
                    j.step = 6;
                    j.waitTicks = PROTOOLS_STEP_DELAY_TICKS;
                } else if (j.confirmBad || j.confirmTicksRemaining <= 0) {
                    if (j.retriesRemainingPos2 > 0) {
                        j.retriesRemainingPos2--;
                        mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[ProtoolsAutomation] Retrying pos2 (" + (j.retriesRemainingPos2 + 1) + " left)..."));
                        resetConfirm(j);
                        j.step = 3;
                        j.waitTicks = TP_RETRY_DELAY_TICKS;
                    } else {
                        fail(mc, "//pos2 could not be set at the target block after retries.");
                    }
                }
                break;
            }

            case 6: {
                if (j.operation == Operation.FILL) {
                    send(mc, "//fill " + j.blockId);
                } else {
                    send(mc, "//set " + j.blockId);
                }
                j.step = 7;
                j.waitTicks = PROTOOLS_STEP_DELAY_TICKS;
                break;
            }

            case 7: {
                // TP back to the player's original position (captured when //setblock was run)
                send(mc, "/tp " + formatCoord(j.returnX) + " " + formatCoord(j.returnY) + " " + formatCoord(j.returnZ));
                // Restore client-side rotation as best-effort.
                mc.thePlayer.rotationYaw = j.returnYaw;
                mc.thePlayer.rotationPitch = j.returnPitch;

                j.step = 8;
                j.waitTicks = RETURN_TO_DESELECT_DELAY_TICKS;
                break;
            }

            case 8: {
                // Deselect the region after returning to avoid leaving a selection behind.
                // (Command name per user request: //desel)
                send(mc, "//desel");
                j.step = 9;
                j.waitTicks = DESELECT_DELAY_TICKS;
                break;
            }

            case 9: {

                mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[ProtoolsAutomation] Done."));
                job = null;
                break;
            }

            default:
                job = null;
                break;
        }
    }

    private static void beginConfirm(Job j, PendingConfirm pending, Minecraft mc) {
        j.pendingConfirm = pending;
        j.confirmTicksRemaining = POS_CONFIRM_TIMEOUT_TICKS;
        j.confirmOk = false;
        j.confirmBad = false;
        j.playerBlockAtPosSend = getPlayerBlockPos(mc);
    }

    private static void resetConfirm(Job j) {
        j.pendingConfirm = PendingConfirm.NONE;
        j.confirmTicksRemaining = 0;
        j.confirmOk = false;
        j.confirmBad = false;
        j.playerBlockAtPosSend = null;
    }

    /**
     * @return true if confirmed OK, false if still waiting or failed.
     */
    private static boolean tickConfirm(Job j, Minecraft mc) {
        BlockPos expected = (j.pendingConfirm == PendingConfirm.POS1) ? j.pos1Target : j.pos2Target;
        if (j.confirmOk) {
            resetConfirm(j);
            return true;
        }

        if (j.confirmBad) {
            // Mismatch (confirmed)
            return false;
        }

        if (j.confirmTicksRemaining > 0) {
            j.confirmTicksRemaining--;
            if (j.confirmTicksRemaining > 0) return false;
        }

        // Timeout: fallback to checking where we were standing when we sent //pos.
        if (expected != null
            && j.playerBlockAtPosSend != null
            && j.playerBlockAtPosSend.getX() == expected.getX()
            && j.playerBlockAtPosSend.getY() == expected.getY()
            && j.playerBlockAtPosSend.getZ() == expected.getZ()) {
            resetConfirm(j);
            return true;
        }

        return false;
    }

    private static BlockPos getPlayerBlockPos(Minecraft mc) {
        if (mc == null || mc.thePlayer == null) return null;
        return new BlockPos(
                (int) Math.floor(mc.thePlayer.posX),
                (int) Math.floor(mc.thePlayer.posY),
                (int) Math.floor(mc.thePlayer.posZ)
        );
    }

    private static boolean isNearTarget(Minecraft mc, BlockPos target) {
        if (mc == null || mc.thePlayer == null || target == null) return false;
        double tx = target.getX() + 0.5;
        double ty = target.getY();
        double tz = target.getZ() + 0.5;

        double dx = Math.abs(mc.thePlayer.posX - tx);
        double dy = Math.abs(mc.thePlayer.posY - ty);
        double dz = Math.abs(mc.thePlayer.posZ - tz);

        return dx <= TP_NEAR_TOLERANCE_XZ && dz <= TP_NEAR_TOLERANCE_XZ && dy <= TP_NEAR_TOLERANCE_Y;
    }

    private static BlockPos parseCoordsFromMessage(String msg) {
        if (msg == null) return null;

        // Common formats:
        // - "(x, y, z)"
        // - "x: 1 y: 2 z: 3"
        // Keep this conservative to avoid false positives.
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\\((-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\)")
                .matcher(msg);
        if (m.find()) {
            return toBlockPos(m);
        }

        m = java.util.regex.Pattern
                .compile("x\\s*[:=]\\s*(-?\\d+).{0,24}y\\s*[:=]\\s*(-?\\d+).{0,24}z\\s*[:=]\\s*(-?\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(msg);
        if (m.find()) {
            return toBlockPos(m);
        }

        // Fallback: if the message contains "pos" and has "x,y,z" with commas.
        m = java.util.regex.Pattern
                .compile("(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)")
                .matcher(msg);
        if (m.find()) {
            return toBlockPos(m);
        }

        return null;
    }

    private static BlockPos toBlockPos(java.util.regex.Matcher m) {
        try {
            int x = Integer.parseInt(m.group(1));
            int y = Integer.parseInt(m.group(2));
            int z = Integer.parseInt(m.group(3));
            return new BlockPos(x, y, z);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void send(Minecraft mc, String message) {
        if (mc == null || mc.thePlayer == null) return;
        mc.thePlayer.sendChatMessage(message);
    }

    private void fail(Minecraft mc, String reason) {
        if (mc != null && mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "[ProtoolsAutomation] " + reason));
        }
        job = null;
    }

    private static void sendTpToBlockCenter(Minecraft mc, BlockPos pos) {
        if (mc == null || mc.thePlayer == null || pos == null) return;
        // Teleport to the center of the block to avoid landing on edges/corners.
        double x = pos.getX() + 0.5;
        double y = pos.getY();
        double z = pos.getZ() + 0.5;
        send(mc, "/tp " + formatCoord(x) + " " + formatCoord(y) + " " + formatCoord(z));
    }

    private static void freezeMovementInputs(Minecraft mc) {
        if (mc == null || mc.gameSettings == null) return;
        // Clear common movement keys.
        setPressed(mc.gameSettings.keyBindForward, false);
        setPressed(mc.gameSettings.keyBindBack, false);
        setPressed(mc.gameSettings.keyBindLeft, false);
        setPressed(mc.gameSettings.keyBindRight, false);
        setPressed(mc.gameSettings.keyBindJump, false);
        setPressed(mc.gameSettings.keyBindSneak, false);
        setPressed(mc.gameSettings.keyBindSprint, false);
    }

    private static void setPressed(KeyBinding binding, boolean pressed) {
        if (binding == null) return;
        try {
            KeyBinding.setKeyBindState(binding.getKeyCode(), pressed);
        } catch (Throwable ignored) {
            // If this fails for any reason, just don't force key state.
        }
    }

    private static String formatCoord(double v) {
        // /tp accepts decimals; keep short + stable
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }
}
