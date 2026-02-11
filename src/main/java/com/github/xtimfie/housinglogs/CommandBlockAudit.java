package com.github.xtimfie.housinglogs;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

public class CommandBlockAudit extends CommandBase {
    @Override
    public String getCommandName() {
        return "hlog";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/hlog add <name> <x1> <y1> <z1> <x2> <y2> <z2> [#RRGGBB|#RRGGBBAA] | /hlog remove <name> | /hlog list | /hlog highlight <name> [on|off] | /hlog clear | /hlog on|off | /hlog path";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[HousingLogs] Usage: " + getCommandUsage(sender)));
            return;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "add": {
                if (args.length != 8 && args.length != 9) {
                    sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[HousingLogs] Usage: /hlog add <name> <x1> <y1> <z1> <x2> <y2> <z2> [#RRGGBB|#RRGGBBAA]"));
                    return;
                }

                String name = args[1];
                try {
                    int x1 = Integer.parseInt(args[2]);
                    int y1 = Integer.parseInt(args[3]);
                    int z1 = Integer.parseInt(args[4]);
                    int x2 = Integer.parseInt(args[5]);
                    int y2 = Integer.parseInt(args[6]);
                    int z2 = Integer.parseInt(args[7]);

                    int color = 0xFFFF00FF;
                    if (args.length == 9) {
                        color = parseColorRgba(args[8], 0xFFFF00FF);
                    }

                    boolean ok = BlockAuditManager.addOrUpdateArea(name, new BlockPos(x1, y1, z1), new BlockPos(x2, y2, z2), color);
                    if (!ok) {
                        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[HousingLogs] Invalid area name."));
                        return;
                    }

                    sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[HousingLogs] Area '" + name + "' added/updated."));
                } catch (NumberFormatException e) {
                    sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[HousingLogs] Coordinates must be integers."));
                }
                break;
            }

            case "clear": {
                BlockAuditManager.clearAreas();
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[HousingLogs] All areas cleared."));
                break;
            }

            case "remove": {
                if (args.length != 2) {
                    sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[HousingLogs] Usage: /hlog remove <name>"));
                    return;
                }
                boolean removed = BlockAuditManager.removeArea(args[1]);
                sender.addChatMessage(new ChatComponentText((removed ? EnumChatFormatting.GREEN : EnumChatFormatting.GRAY)
                        + "[HousingLogs] " + (removed ? "Removed" : "No such area") + ": " + args[1]));
                break;
            }

            case "list":
            case "status": {
                java.util.List<BlockAuditManager.AreaSnapshot> areas = BlockAuditManager.getAreasSnapshot();
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[HousingLogs] Logging " + (BlockAuditManager.isEnabled() ? "enabled" : "disabled") + ". Areas: " + areas.size()));
                for (BlockAuditManager.AreaSnapshot a : areas) {
                    sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[HousingLogs] - " + a.name
                            + " highlight=" + (a.highlight ? "on" : "off")
                            + " color=" + toHexRgba(a.colorRgba)
                            + " (" + a.min.getX() + "," + a.min.getY() + "," + a.min.getZ() + ") -> ("
                            + a.max.getX() + "," + a.max.getY() + "," + a.max.getZ() + ")"));
                }
                break;
            }

            case "on": {
                BlockAuditManager.setEnabled(true);
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[HousingLogs] Logging enabled."));
                break;
            }

            case "off": {
                BlockAuditManager.setEnabled(false);
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[HousingLogs] Logging disabled."));
                break;
            }

            case "highlight": {
                if (args.length != 2 && args.length != 3) {
                    sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[HousingLogs] Usage: /hlog highlight <name> [on|off]"));
                    return;
                }

                boolean ok;
                if (args.length == 2) {
                    ok = BlockAuditManager.toggleAreaHighlight(args[1]);
                } else {
                    String v = args[2].toLowerCase();
                    if (!v.equals("on") && !v.equals("off")) {
                        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[HousingLogs] Usage: /hlog highlight <name> [on|off]"));
                        return;
                    }
                    ok = BlockAuditManager.setAreaHighlight(args[1], v.equals("on"));
                }

                sender.addChatMessage(new ChatComponentText((ok ? EnumChatFormatting.GREEN : EnumChatFormatting.GRAY)
                        + "[HousingLogs] " + (ok ? "Updated highlight for" : "No such area") + ": " + args[1]));
                break;
            }

            case "path": {
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[HousingLogs] Files:"));
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[HousingLogs] Areas: " + BlockAuditManager.getAreaFile().getAbsolutePath()));
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[HousingLogs] JSONL: " + BlockAuditManager.getJsonlLogFile().getAbsolutePath()));
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[HousingLogs] LOG: " + BlockAuditManager.getTextLogFile().getAbsolutePath()));
                break;
            }

            default:
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[HousingLogs] Usage: " + getCommandUsage(sender)));
                break;
        }
    }

    private static int parseColorRgba(String s, int fallback) {
        if (s == null) return fallback;
        String t = s.trim();
        if (t.isEmpty()) return fallback;
        if (t.charAt(0) == '#') t = t.substring(1);
        if (!(t.length() == 6 || t.length() == 8)) return fallback;
        try {
            int r = Integer.parseInt(t.substring(0, 2), 16);
            int g = Integer.parseInt(t.substring(2, 4), 16);
            int b = Integer.parseInt(t.substring(4, 6), 16);
            int a = (t.length() == 8) ? Integer.parseInt(t.substring(6, 8), 16) : 0xFF;
            return (r << 24) | (g << 16) | (b << 8) | a;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String toHexRgba(int rgba) {
        int r = (rgba >> 24) & 0xFF;
        int g = (rgba >> 16) & 0xFF;
        int b = (rgba >> 8) & 0xFF;
        int a = rgba & 0xFF;
        return String.format(java.util.Locale.ROOT, "#%02X%02X%02X%02X", r, g, b, a);
    }
}
