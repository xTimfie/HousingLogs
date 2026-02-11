package com.github.xtimfie.housinglogs;

import net.minecraft.client.Minecraft;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;

final class HousingContext {
    private HousingContext() {
    }

    static boolean isInHousing() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) return false;

        Scoreboard sb = mc.theWorld.getScoreboard();
        if (sb == null) return false;

        for (ScoreObjective obj : sb.getScoreObjectives()) {
            String displayName = EnumChatFormatting.getTextWithoutFormattingCodes(obj.getDisplayName());
            if (displayName != null && displayName.contains("HOUSING")) {
                return true;
            }
        }
        return false;
    }
}