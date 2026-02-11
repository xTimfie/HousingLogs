package com.github.xtimfie.housinglogs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Vec3;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client-side block audit logging for multiple named cuboid areas.
 *
 * Limitation: servers typically do not send reliable "who placed" info to clients.
 * Break attribution is best-effort via break animation packets.
 */
public class BlockAuditManager {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    // Single-threaded usage via LOG_EXECUTOR, so SimpleDateFormat thread-safety is not an issue here.
    private static final java.text.SimpleDateFormat TEXT_LOG_TIME = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT);

    private static final File AREA_FILE = new File(Minecraft.getMinecraft().mcDataDir, "config/hitlist-blockaudit-area.json");
    private static final File LOG_FILE = new File(Minecraft.getMinecraft().mcDataDir, "config/hitlist-blockaudit-log.jsonl");
    private static final File LOG_FILE_TEXT = new File(Minecraft.getMinecraft().mcDataDir, "config/hitlist-blockaudit.log");

    private static final ExecutorService LOG_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Hitlist-BlockAudit-Log");
        t.setDaemon(true);
        return t;
    });

    private static final int DEFAULT_COLOR_RGBA = 0xFFFF00FF; // #FFFF00FF (yellow)

    private static boolean globallyEnabled = true;

    private static final class Area {
        final String key;
        final String name;
        final BlockPos min;
        final BlockPos max;
        final int colorRgba;
        final boolean enabled;
        final boolean highlight;

        private Area(String key, String name, BlockPos min, BlockPos max, int colorRgba, boolean enabled, boolean highlight) {
            this.key = key;
            this.name = name;
            this.min = min;
            this.max = max;
            this.colorRgba = colorRgba;
            this.enabled = enabled;
            this.highlight = highlight;
        }

        boolean contains(BlockPos pos) {
            return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                    && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                    && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
        }
    }

    public static final class AreaSnapshot {
        public final String name;
        public final BlockPos min;
        public final BlockPos max;
        public final int colorRgba;
        public final boolean enabled;
        public final boolean highlight;

        private AreaSnapshot(String name, BlockPos min, BlockPos max, int colorRgba, boolean enabled, boolean highlight) {
            this.name = name;
            this.min = min;
            this.max = max;
            this.colorRgba = colorRgba;
            this.enabled = enabled;
            this.highlight = highlight;
        }
    }

    private static final Map<String, Area> AREAS = new LinkedHashMap<>();

    private static final Map<BlockPos, BreakAttribution> recentBreakers = new HashMap<>();
    private static final long BREAK_ATTRIBUTION_WINDOW_MS = 2500;

    private static final double PLACER_MAX_DIST = 7.0;
    private static final double PLACER_LOOK_DOT_MIN = 0.94; // widened for stability (heuristic is best-effort)
    private static final double BREAKER_LOOK_DOT_MIN = 0.92; // slightly wider than placement
    private static final double UNKNOWN_BREAK_NEARBY_RADIUS = 5.0;

    private static class BreakAttribution {
        final UUID uuid;
        final String name;
        final long atMs;

        BreakAttribution(UUID uuid, String name, long atMs) {
            this.uuid = uuid;
            this.name = name;
            this.atMs = atMs;
        }
    }

    public static boolean isEnabled() {
        return globallyEnabled;
    }

    public static void setEnabled(boolean enabled) {
        globallyEnabled = enabled;
        if (enabled && hasAnyEnabledArea()) ensureLogFilesExistAsync();
        saveAreaToDiskAsync();
    }

    public static boolean hasAnyArea() {
        synchronized (AREAS) {
            return !AREAS.isEmpty();
        }
    }

    private static boolean hasAnyEnabledArea() {
        synchronized (AREAS) {
            for (Area a : AREAS.values()) {
                if (a.enabled) return true;
            }
            return false;
        }
    }

    public static boolean addOrUpdateArea(String name, BlockPos a, BlockPos b, int colorRgba) {
        if (name == null) return false;
        if (a == null || b == null) return false;

        String trimmed = name.trim();
        if (trimmed.isEmpty()) return false;

        String key = normalizeKey(trimmed);

        int minX = Math.min(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxX = Math.max(a.getX(), b.getX());
        int maxY = Math.max(a.getY(), b.getY());
        int maxZ = Math.max(a.getZ(), b.getZ());

        BlockPos min = new BlockPos(minX, minY, minZ);
        BlockPos max = new BlockPos(maxX, maxY, maxZ);

        synchronized (AREAS) {
            Area prev = AREAS.get(key);
            boolean highlight = prev != null && prev.highlight;
            boolean enabled = prev == null || prev.enabled;
            AREAS.put(key, new Area(key, trimmed, min, max, colorRgba, enabled, highlight));
        }

        if (globallyEnabled) ensureLogFilesExistAsync();
        saveAreaToDiskAsync();
        return true;
    }

    public static boolean removeArea(String name) {
        if (name == null) return false;
        String key = normalizeKey(name);
        boolean removed;
        synchronized (AREAS) {
            removed = AREAS.remove(key) != null;
        }
        if (removed) saveAreaToDiskAsync();
        return removed;
    }

    public static void clearAreas() {
        synchronized (AREAS) {
            AREAS.clear();
        }
        recentBreakers.clear();
        saveAreaToDiskAsync();
    }

    public static boolean toggleAreaHighlight(String name) {
        if (name == null) return false;
        String key = normalizeKey(name);
        synchronized (AREAS) {
            Area a = AREAS.get(key);
            if (a == null) return false;
            AREAS.put(key, new Area(a.key, a.name, a.min, a.max, a.colorRgba, a.enabled, !a.highlight));
        }
        saveAreaToDiskAsync();
        return true;
    }

    public static boolean setAreaHighlight(String name, boolean highlight) {
        if (name == null) return false;
        String key = normalizeKey(name);
        synchronized (AREAS) {
            Area a = AREAS.get(key);
            if (a == null) return false;
            AREAS.put(key, new Area(a.key, a.name, a.min, a.max, a.colorRgba, a.enabled, highlight));
        }
        saveAreaToDiskAsync();
        return true;
    }

    public static List<AreaSnapshot> getAreasSnapshot() {
        synchronized (AREAS) {
            List<AreaSnapshot> out = new ArrayList<>(AREAS.size());
            for (Area a : AREAS.values()) {
                out.add(new AreaSnapshot(a.name, a.min, a.max, a.colorRgba, a.enabled, a.highlight));
            }
            return out;
        }
    }

    public static List<AreaSnapshot> getHighlightAreasSnapshot() {
        synchronized (AREAS) {
            List<AreaSnapshot> out = new ArrayList<>();
            for (Area a : AREAS.values()) {
                if (!a.highlight) continue;
                out.add(new AreaSnapshot(a.name, a.min, a.max, a.colorRgba, a.enabled, true));
            }
            return out;
        }
    }

    public static File getJsonlLogFile() {
        return LOG_FILE;
    }

    public static File getTextLogFile() {
        return LOG_FILE_TEXT;
    }

    public static File getAreaFile() {
        return AREA_FILE;
    }

    private static void ensureLogFilesExistAsync() {
        LOG_EXECUTOR.execute(() -> {
            try {
                LOG_FILE.getParentFile().mkdirs();
                // Touch files (create if missing)
                try (Writer ignored = new OutputStreamWriter(new FileOutputStream(LOG_FILE, true), StandardCharsets.UTF_8)) {
                }
                try (Writer ignored = new OutputStreamWriter(new FileOutputStream(LOG_FILE_TEXT, true), StandardCharsets.UTF_8)) {
                }
            } catch (IOException e) {
                System.err.println("[HousingLogs] Failed to create block audit log files: " + e.getMessage());
            }
        });
    }

        private static List<Area> snapshotMatchingAreas(BlockPos pos) {
            if (pos == null) return null;
            synchronized (AREAS) {
                if (AREAS.isEmpty()) return null;
                List<Area> out = null;
                for (Area a : AREAS.values()) {
                    if (!a.enabled) continue;
                    if (!a.contains(pos)) continue;
                    if (out == null) out = new ArrayList<>();
                    out.add(a);
                }
                return out;
            }
        }

    /**
     * Called from packet hook (main thread). Associates a breaker with a position when a break animation finishes.
     */
    public static void noteBlockBreakAnim(BlockPos pos, int breakerEntityId, int progress) {
        if (!globallyEnabled || pos == null) return;
        List<Area> match = snapshotMatchingAreas(pos);
        if (match == null || match.isEmpty()) return;
        if (progress != -1) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) return;

        Entity entity = mc.theWorld.getEntityByID(breakerEntityId);
        if (!(entity instanceof EntityPlayer)) return;

        EntityPlayer p = (EntityPlayer) entity;
        synchronized (recentBreakers) {
            recentBreakers.put(new BlockPos(pos), new BreakAttribution(p.getUniqueID(), p.getName(), System.currentTimeMillis()));
        }
    }

    /**
     * Called from packet hook (main thread) before the world applies the new state.
     */
    public static void noteBlockChange(BlockPos pos, IBlockState oldState, IBlockState newState) {
        noteBlockChange(pos, oldState, newState, true);
    }

    /**
     * @param allowHeuristic When false, skip placer guessing (useful for multi-block updates to reduce lag/false positives).
     */
    public static void noteBlockChange(BlockPos pos, IBlockState oldState, IBlockState newState, boolean allowHeuristic) {
        if (!globallyEnabled || pos == null) return;
        List<Area> matchingAreas = snapshotMatchingAreas(pos);
        if (matchingAreas == null || matchingAreas.isEmpty()) return;
        if (!HousingContext.isInHousing()) return;

        if (oldState == null || newState == null) return;
        if (oldState == newState) return;

        String action;
        boolean oldAir = oldState.getBlock().getMaterial().isReplaceable() || oldState.getBlock().isAir(Minecraft.getMinecraft().theWorld, pos);
        boolean newAir = newState.getBlock().getMaterial().isReplaceable() || newState.getBlock().isAir(Minecraft.getMinecraft().theWorld, pos);

        if (oldAir && !newAir) action = "PLACE";
        else if (!oldAir && newAir) action = "BREAK";
        else action = "CHANGE";

        UUID actorUuid = null;
        String actorName = null;
        String attribution = "unknown";

        if ("BREAK".equals(action)) {
            BreakAttribution ba;
            synchronized (recentBreakers) {
                ba = recentBreakers.get(pos);
            }
            if (ba != null && (System.currentTimeMillis() - ba.atMs) <= BREAK_ATTRIBUTION_WINDOW_MS) {
                actorUuid = ba.uuid;
                actorName = ba.name;
                attribution = "break_anim";
            }
        }

        // Snapshot fields on the main thread; do heuristic + JSON + file I/O off-thread.
        final long tsMs = System.currentTimeMillis();
        final int x = pos.getX();
        final int y = pos.getY();
        final int z = pos.getZ();

        Integer dimension = null;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.theWorld != null && mc.theWorld.provider != null) {
                dimension = mc.theWorld.provider.getDimensionId();
            }
        } catch (Throwable ignored) {
        }
        final Integer dimensionFinal = dimension;

        final BlockStateSnapshot oldSnap = snapshotState(oldState);
        final BlockStateSnapshot newSnap = snapshotState(newState);

        final List<PlacementCandidate> candidates;
        if (allowHeuristic && ("PLACE".equals(action) || "BREAK".equals(action)) && actorUuid == null) {
            candidates = snapshotPlacementCandidates(pos, newState);
        } else {
            candidates = null;
        }

        final List<NearbyPlayerSnapshot> nearbyPlayers;
        if ("BREAK".equals(action) && actorUuid == null) {
            nearbyPlayers = snapshotNearbyPlayers(pos, UNKNOWN_BREAK_NEARBY_RADIUS);
        } else {
            nearbyPlayers = null;
        }

        final UUID initialActorUuid = actorUuid;
        final String initialActorName = actorName;
        final String initialAttribution = attribution;
        final String actionFinal = action;
        final List<String> areaNames;
        {
            List<String> tmp = new ArrayList<>(matchingAreas.size());
            for (Area a : matchingAreas) {
                tmp.add(a.name);
            }
            areaNames = tmp;
        }

        LOG_EXECUTOR.execute(() -> {
            UUID finalActorUuid = initialActorUuid;
            String finalActorName = initialActorName;
            String finalAttribution = initialAttribution;

            if (candidates != null && !candidates.isEmpty()) {
                PlacementGuess guess = guessActorFromCandidates(x, y, z, newSnap, candidates, "BREAK".equals(actionFinal));
                if (guess != null) {
                    finalActorUuid = guess.uuid;
                    finalActorName = guess.name;
                    if ("BREAK".equals(actionFinal)) {
                        finalAttribution = "heuristic_break";
                    } else {
                        finalAttribution = guess.itemMatch ? "heuristic_look_item" : "heuristic_look";
                    }
                }
            }

            for (String areaName : areaNames) {
                JsonObject entry = makeEntry(areaName, tsMs, x, y, z, dimensionFinal, actionFinal, oldSnap, newSnap, finalActorUuid, finalActorName, finalAttribution);

                if ("BREAK".equals(actionFinal)
                        && (finalActorUuid == null || finalActorName == null)
                        && nearbyPlayers != null
                        && !nearbyPlayers.isEmpty()) {
                    com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                    for (NearbyPlayerSnapshot np : nearbyPlayers) {
                        JsonObject p = new JsonObject();
                        if (np.uuid != null) p.addProperty("uuid", np.uuid.toString());
                        if (np.name != null) p.addProperty("name", np.name);
                        p.addProperty("dist", np.dist);
                        arr.add(p);
                    }
                    entry.add("nearbyPlayers", arr);
                }

                appendLogLine(entry);
            }
        });
    }

    private static class BlockStateSnapshot {
        final String block;
        final int meta;

        BlockStateSnapshot(String block, int meta) {
            this.block = block;
            this.meta = meta;
        }
    }

    private static class PlacementCandidate {
        final UUID uuid;
        final String name;
        final double eyeX, eyeY, eyeZ;
        final double lookX, lookY, lookZ;
        final boolean itemMatch;

        private PlacementCandidate(UUID uuid, String name,
                                  double eyeX, double eyeY, double eyeZ,
                                  double lookX, double lookY, double lookZ,
                                  boolean itemMatch) {
            this.uuid = uuid;
            this.name = name;
            this.eyeX = eyeX;
            this.eyeY = eyeY;
            this.eyeZ = eyeZ;
            this.lookX = lookX;
            this.lookY = lookY;
            this.lookZ = lookZ;
            this.itemMatch = itemMatch;
        }
    }

    private static class PlacementGuess {
        final UUID uuid;
        final String name;
        final boolean itemMatch;

        private PlacementGuess(UUID uuid, String name, boolean itemMatch) {
            this.uuid = uuid;
            this.name = name;
            this.itemMatch = itemMatch;
        }
    }

    private static class NearbyPlayerSnapshot {
        final UUID uuid;
        final String name;
        final double dist;

        NearbyPlayerSnapshot(UUID uuid, String name, double dist) {
            this.uuid = uuid;
            this.name = name;
            this.dist = dist;
        }
    }

    private static BlockStateSnapshot snapshotState(IBlockState state) {
        try {
            Block block = state.getBlock();
            ResourceLocation key = (ResourceLocation) Block.blockRegistry.getNameForObject(block);
            String name = key == null ? "unknown" : key.toString();
            int meta;
            try {
                meta = block.getMetaFromState(state);
            } catch (Throwable t) {
                meta = 0;
            }
            return new BlockStateSnapshot(name, meta);
        } catch (Throwable t) {
            return new BlockStateSnapshot("unknown", 0);
        }
    }

    private static List<PlacementCandidate> snapshotPlacementCandidates(BlockPos pos, IBlockState newState) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) return null;

        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;

        List<PlacementCandidate> out = new ArrayList<>();

        for (Object obj : mc.theWorld.playerEntities) {
            if (!(obj instanceof EntityPlayer)) continue;
            EntityPlayer p = (EntityPlayer) obj;

            double eyeX = p.posX;
            double eyeY = p.posY + p.getEyeHeight();
            double eyeZ = p.posZ;

            double dx = cx - eyeX;
            double dy = cy - eyeY;
            double dz = cz - eyeZ;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq <= 0.0001) continue;
            if (distSq > (PLACER_MAX_DIST * PLACER_MAX_DIST)) continue;

            Vec3 look = p.getLookVec();
            if (look == null) continue;

            boolean itemMatch = false;
            try {
                ItemStack held = p.getHeldItem();
                if (held != null) {
                    Item item = held.getItem();
                    Block heldBlock = Block.getBlockFromItem(item);
                    if (heldBlock != null && heldBlock == newState.getBlock()) {
                        itemMatch = true;
                    }
                }
            } catch (Throwable ignored) {
            }

            out.add(new PlacementCandidate(
                    p.getUniqueID(),
                    p.getName(),
                    eyeX, eyeY, eyeZ,
                    look.xCoord, look.yCoord, look.zCoord,
                    itemMatch
            ));
        }

        return out;
    }

    private static List<NearbyPlayerSnapshot> snapshotNearbyPlayers(BlockPos pos, double radius) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) return null;

        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;
        double r2 = radius * radius;

        List<NearbyPlayerSnapshot> out = new ArrayList<>();

        for (Object obj : mc.theWorld.playerEntities) {
            if (!(obj instanceof EntityPlayer)) continue;
            EntityPlayer p = (EntityPlayer) obj;

            double dx = cx - p.posX;
            double dy = cy - p.posY;
            double dz = cz - p.posZ;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > r2) continue;

            out.add(new NearbyPlayerSnapshot(p.getUniqueID(), p.getName(), Math.sqrt(d2)));
        }

        out.sort((a, b) -> Double.compare(a.dist, b.dist));
        return out;
    }

    private static PlacementGuess guessActorFromCandidates(int x, int y, int z, BlockStateSnapshot newSnap, List<PlacementCandidate> candidates, boolean isBreak) {
        if (candidates == null || candidates.isEmpty()) return null;

        double cx = x + 0.5;
        double cy = y + 0.5;
        double cz = z + 0.5;

        PlacementCandidate best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (PlacementCandidate c : candidates) {
            double dx = cx - c.eyeX;
            double dy = cy - c.eyeY;
            double dz = cz - c.eyeZ;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq <= 0.0001) continue;

            double dist = Math.sqrt(distSq);
            if (dist > PLACER_MAX_DIST) continue;

            // More stable heuristic:
            // - Use distance from the player's view ray to the block center (perpendicular distance)
            // - Keep a mild dot-product gate to avoid behind-the-player guesses
            double lookLenSq = (c.lookX * c.lookX) + (c.lookY * c.lookY) + (c.lookZ * c.lookZ);
            if (lookLenSq <= 0.0001) continue;
            double invLookLen = 1.0 / Math.sqrt(lookLenSq);
            double lx = c.lookX * invLookLen;
            double ly = c.lookY * invLookLen;
            double lz = c.lookZ * invLookLen;

            double dot = (lx * dx + ly * dy + lz * dz) / dist; // cos(angle)
            if (dot < (isBreak ? BREAKER_LOOK_DOT_MIN : PLACER_LOOK_DOT_MIN)) continue;

            double t = (lx * dx) + (ly * dy) + (lz * dz); // projection length along ray (in blocks)
            if (t < 0.0) continue;

            double closestX = c.eyeX + (lx * t);
            double closestY = c.eyeY + (ly * t);
            double closestZ = c.eyeZ + (lz * t);
            double pdx = cx - closestX;
            double pdy = cy - closestY;
            double pdz = cz - closestZ;
            double perpDistSq = (pdx * pdx) + (pdy * pdy) + (pdz * pdz);

            double maxPerp = isBreak ? 2.25 : 1.75; // squared distance to ray; break is a bit looser
            if (perpDistSq > maxPerp) continue;

            double score = 2.2 * dot;
            score -= (dist / PLACER_MAX_DIST);
            score -= (perpDistSq * (isBreak ? 0.75 : 0.95));
            if (!isBreak && c.itemMatch) score += 0.35;

            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }

        if (best == null) return null;
        if (bestScore < 0.25) return null;
        return new PlacementGuess(best.uuid, best.name, best.itemMatch);
    }

    private static JsonObject makeEntry(String areaName, long tsMs, int x, int y, int z, Integer dimension, String action,
                                        BlockStateSnapshot oldSnap, BlockStateSnapshot newSnap,
                                        UUID actorUuid, String actorName, String attribution) {
        JsonObject obj = new JsonObject();
        obj.addProperty("tsMs", tsMs);
        if (areaName != null) obj.addProperty("area", areaName);
        obj.addProperty("action", action);
        obj.addProperty("x", x);
        obj.addProperty("y", y);
        obj.addProperty("z", z);
        obj.addProperty("inHousing", true);
        if (dimension != null) obj.addProperty("dimension", dimension);

        obj.addProperty("oldBlock", oldSnap.block);
        obj.addProperty("oldMeta", oldSnap.meta);
        obj.addProperty("newBlock", newSnap.block);
        obj.addProperty("newMeta", newSnap.meta);

        if (actorUuid != null) obj.addProperty("playerUuid", actorUuid.toString());
        if (actorName != null) obj.addProperty("playerName", actorName);
        obj.addProperty("attribution", attribution);

        return obj;
    }

    private static void appendLogLine(JsonObject entry) {
        final String line = GSON.toJson(entry);
        try {
            LOG_FILE.getParentFile().mkdirs();
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(LOG_FILE, true), StandardCharsets.UTF_8)) {
                writer.write(line);
                writer.write("\n");
            }

            // Also write a human-readable .log line for "tail"-style viewing.
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(LOG_FILE_TEXT, true), StandardCharsets.UTF_8)) {
                writer.write(toTextLine(entry));
                writer.write("\n");
            }
        } catch (IOException e) {
            System.err.println("[HousingLogs] Failed to write block audit log: " + e.getMessage());
        }
    }

    private static String toTextLine(JsonObject entry) {
        // Format example:
        // [12:34:56] PLACE (x,y,z) stone:0 -> wool:14 player=Name uuid=... attr=heuristic_look
        StringBuilder sb = new StringBuilder();
        try {
            long ts = entry.has("tsMs") ? entry.get("tsMs").getAsLong() : 0L;
            sb.append('[').append(TEXT_LOG_TIME.format(new java.util.Date(ts))).append("] ");

            if (entry.has("area")) {
                sb.append('[').append(entry.get("area").getAsString()).append("] ");
            }

            sb.append(entry.has("action") ? entry.get("action").getAsString() : "?");
            sb.append(" (");
            sb.append(entry.has("x") ? entry.get("x").getAsInt() : 0).append(',');
            sb.append(entry.has("y") ? entry.get("y").getAsInt() : 0).append(',');
            sb.append(entry.has("z") ? entry.get("z").getAsInt() : 0).append(')');
            sb.append(' ');
            sb.append(entry.has("oldBlock") ? entry.get("oldBlock").getAsString() : "?");
            sb.append(':');
            sb.append(entry.has("oldMeta") ? entry.get("oldMeta").getAsInt() : 0);
            sb.append(" -> ");
            sb.append(entry.has("newBlock") ? entry.get("newBlock").getAsString() : "?");
            sb.append(':');
            sb.append(entry.has("newMeta") ? entry.get("newMeta").getAsInt() : 0);

            if (entry.has("playerName")) {
                sb.append(" player=").append(entry.get("playerName").getAsString());
            } else {
                sb.append(" player=unknown");
            }

            if (entry.has("playerUuid")) {
                sb.append(" uuid=").append(entry.get("playerUuid").getAsString());
            }

            sb.append(" attr=").append(entry.has("attribution") ? entry.get("attribution").getAsString() : "unknown");

            if (entry.has("nearbyPlayers") && entry.get("nearbyPlayers").isJsonArray()) {
                com.google.gson.JsonArray arr = entry.getAsJsonArray("nearbyPlayers");
                if (arr.size() > 0) {
                    sb.append(" nearby=[");
                    int limit = Math.min(arr.size(), 12);
                    for (int i = 0; i < limit; i++) {
                        if (i > 0) sb.append(", ");
                        if (!arr.get(i).isJsonObject()) continue;
                        JsonObject p = arr.get(i).getAsJsonObject();
                        String n = p.has("name") ? p.get("name").getAsString() : "?";
                        double d = p.has("dist") ? p.get("dist").getAsDouble() : -1.0;
                        sb.append(n);
                        if (d >= 0) sb.append('(').append(String.format(java.util.Locale.ROOT, "%.2f", d)).append(')');
                    }
                    if (arr.size() > limit) sb.append(", ...");
                    sb.append(']');
                }
            }
        } catch (Throwable t) {
            return lineFallback(entry);
        }
        return sb.toString();
    }

    private static String lineFallback(JsonObject entry) {
        try {
            return GSON.toJson(entry);
        } catch (Throwable t) {
            return "{\"error\":\"failed_to_format\"}";
        }
    }

    public static void loadAreaFromDisk() {
        if (!AREA_FILE.exists()) return;
        try (Reader reader = new InputStreamReader(new FileInputStream(AREA_FILE), StandardCharsets.UTF_8)) {
            JsonObject obj = GSON.fromJson(reader, JsonObject.class);
            if (obj == null) return;

            globallyEnabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();

            synchronized (AREAS) {
                AREAS.clear();

                if (obj.has("areas") && obj.get("areas").isJsonArray()) {
                    JsonArray arr = obj.getAsJsonArray("areas");
                    for (int i = 0; i < arr.size(); i++) {
                        if (!arr.get(i).isJsonObject()) continue;
                        JsonObject a = arr.get(i).getAsJsonObject();
                        if (!a.has("name")) continue;

                        String name = a.get("name").getAsString();
                        String key = normalizeKey(name);
                        boolean enabled = !a.has("enabled") || a.get("enabled").getAsBoolean();
                        boolean highlight = a.has("highlight") && a.get("highlight").getAsBoolean();
                        int color = a.has("color") ? parseColorRgba(a.get("color").getAsString(), DEFAULT_COLOR_RGBA) : DEFAULT_COLOR_RGBA;

                        BlockPos min = null;
                        BlockPos max = null;
                        if (a.has("min") && a.has("max")) {
                            JsonObject mn = a.getAsJsonObject("min");
                            JsonObject mx = a.getAsJsonObject("max");
                            if (mn != null && mx != null) {
                                min = new BlockPos(mn.get("x").getAsInt(), mn.get("y").getAsInt(), mn.get("z").getAsInt());
                                max = new BlockPos(mx.get("x").getAsInt(), mx.get("y").getAsInt(), mx.get("z").getAsInt());
                            }
                        }
                        if (min == null || max == null) continue;

                        AREAS.put(key, new Area(key, name, min, max, color, enabled, highlight));
                    }
                } else if (obj.has("min") && obj.has("max")) {
                    // Legacy single-area format migration: store as an area named "default".
                    JsonObject mn = obj.getAsJsonObject("min");
                    JsonObject mx = obj.getAsJsonObject("max");
                    if (mn != null && mx != null) {
                        BlockPos min = new BlockPos(mn.get("x").getAsInt(), mn.get("y").getAsInt(), mn.get("z").getAsInt());
                        BlockPos max = new BlockPos(mx.get("x").getAsInt(), mx.get("y").getAsInt(), mx.get("z").getAsInt());
                        boolean enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
                        boolean highlight = obj.has("highlight") && obj.get("highlight").getAsBoolean();
                        String name = "default";
                        String key = normalizeKey(name);
                        AREAS.put(key, new Area(key, name, min, max, DEFAULT_COLOR_RGBA, enabled, highlight));
                    }
                }
            }

            if (globallyEnabled && hasAnyEnabledArea()) ensureLogFilesExistAsync();
        } catch (Exception e) {
            System.err.println("[HousingLogs] Failed to load block audit areas: " + e.getMessage());
        }
    }

    private static void saveAreaToDiskAsync() {
        final JsonObject obj = new JsonObject();
        obj.addProperty("enabled", globallyEnabled);

        JsonArray areasArr = new JsonArray();
        synchronized (AREAS) {
            for (Area a : AREAS.values()) {
                JsonObject area = new JsonObject();
                area.addProperty("name", a.name);
                area.addProperty("enabled", a.enabled);
                area.addProperty("highlight", a.highlight);
                area.addProperty("color", colorToHexRgba(a.colorRgba));

                JsonObject mn = new JsonObject();
                mn.addProperty("x", a.min.getX());
                mn.addProperty("y", a.min.getY());
                mn.addProperty("z", a.min.getZ());
                area.add("min", mn);

                JsonObject mx = new JsonObject();
                mx.addProperty("x", a.max.getX());
                mx.addProperty("y", a.max.getY());
                mx.addProperty("z", a.max.getZ());
                area.add("max", mx);

                areasArr.add(area);
            }
        }
        obj.add("areas", areasArr);

        LOG_EXECUTOR.execute(() -> {
            try {
                AREA_FILE.getParentFile().mkdirs();
                try (Writer writer = new OutputStreamWriter(new FileOutputStream(AREA_FILE), StandardCharsets.UTF_8)) {
                    GSON.toJson(obj, writer);
                }
            } catch (IOException e) {
                System.err.println("[HousingLogs] Failed to save block audit areas: " + e.getMessage());
            }
        });
    }

    private static String normalizeKey(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
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

    private static String colorToHexRgba(int rgba) {
        int r = (rgba >> 24) & 0xFF;
        int g = (rgba >> 16) & 0xFF;
        int b = (rgba >> 8) & 0xFF;
        int a = rgba & 0xFF;
        return String.format(Locale.ROOT, "#%02X%02X%02X%02X", r, g, b, a);
    }
}
