package com.gildedmc.core.modules;

import com.sk89q.jnbt.CompoundTag;
import com.sk89q.jnbt.LongArrayTag;
import com.sk89q.jnbt.NBTInputStream;
import com.sk89q.jnbt.NamedTag;
import com.sk89q.jnbt.Tag;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.block.BlockState;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

/**
 * Reads Litematica {@code .litematic} files into WorldEdit clipboards.
 *
 * <p>Litematica stores regions of palette-packed block states (the same
 * packing vanilla chunk sections use: X fastest, then Z, then Y, at least
 * 2 bits per entry). This parses the NBT with WorldEdit's bundled JNBT,
 * unpacks every region, and merges them into one clipboard. Block strings
 * are adapted through the caller-supplied function so this class never
 * touches Bukkit itself.
 *
 * <p>Translated clipboards are cached by path + size + mtime (cap
 * {@value #CACHE_CAP}, eldest evicted): translation walks every block,
 * and the same stash file gets pasted repeatedly.
 *
 * <p>Tile entities and entities are intentionally skipped — pasted stashes
 * are structural decoys, and containers paste empty.
 */
final class LitematicReader {

    private LitematicReader() { }

    /** Cap on cached translations. */
    static final int CACHE_CAP = 20;

    private record Cached(long lastModified, long length, Clipboard clipboard) { }

    private static final Map<String, Cached> CACHE = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > CACHE_CAP;
        }
    };

    /**
     * Reads a {@code .litematic} file, translating block strings with
     * {@code adapter} (e.g. Bukkit block-data parsing, which needs a server).
     */
    static synchronized Clipboard read(File file, Function<String, BlockState> adapter)
            throws IOException {
        String key = file.getAbsolutePath();
        long modified = file.lastModified();
        long length = file.length();
        Cached cached = CACHE.get(key);
        if (cached != null && cached.lastModified() == modified && cached.length() == length) {
            return cached.clipboard();
        }
        Clipboard clipboard = translate(file, adapter);
        CACHE.put(key, new Cached(modified, length, clipboard));
        return clipboard;
    }

    /** Drops a file from the cache, e.g. after it changed on disk. */
    static synchronized void invalidate(File file) {
        CACHE.remove(file.getAbsolutePath());
    }

    static synchronized void clear() {
        CACHE.clear();
    }

    private static Clipboard translate(File file, Function<String, BlockState> adapter)
            throws IOException {
        List<RegionBlocks> parts = readRegions(file);
        if (parts.isEmpty()) {
            throw new IOException("no readable regions in " + file.getName());
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (RegionBlocks part : parts) {
            minX = Math.min(minX, part.originX);
            minY = Math.min(minY, part.originY);
            minZ = Math.min(minZ, part.originZ);
            maxX = Math.max(maxX, part.originX + part.sizeX - 1);
            maxY = Math.max(maxY, part.originY + part.sizeY - 1);
            maxZ = Math.max(maxZ, part.originZ + part.sizeZ - 1);
        }
        CuboidRegion bounds = new CuboidRegion(
                BlockVector3.at(minX, minY, minZ), BlockVector3.at(maxX, maxY, maxZ));
        BlockArrayClipboard clipboard = new BlockArrayClipboard(bounds);
        clipboard.setOrigin(BlockVector3.at(minX, minY, minZ));

        for (RegionBlocks part : parts) {
            for (int y = 0; y < part.sizeY; y++) {
                for (int z = 0; z < part.sizeZ; z++) {
                    for (int x = 0; x < part.sizeX; x++) {
                        String name = part.at(x, y, z);
                        if (name == null) continue;
                        BlockState state;
                        try {
                            state = adapter.apply(name);
                        } catch (Throwable ignored) {
                            continue;
                        }
                        if (state == null) continue;
                        clipboard.setBlock(BlockVector3.at(
                                part.originX + x, part.originY + y, part.originZ + z), state);
                    }
                }
            }
        }
        return clipboard;
    }

    record RegionBlocks(int originX, int originY, int originZ,
                        int sizeX, int sizeY, int sizeZ,
                        String[] names) {
        String at(int x, int y, int z) {
            return names[(y * sizeZ + z) * sizeX + x];
        }
    }

    /** Parses every region of a file to block-name grids. Probe seam: no Bukkit needed. */
    static List<RegionBlocks> readRegions(File file) throws IOException {
        CompoundTag root;
        try (InputStream in = new BufferedInputStream(new FileInputStream(file));
             GZIPInputStream gzip = new GZIPInputStream(in);
             NBTInputStream nbt = new NBTInputStream(gzip)) {
            NamedTag named = nbt.readNamedTag();
            if (!(named.getTag() instanceof CompoundTag compound)) {
                throw new IOException("not an NBT compound: " + file.getName());
            }
            root = compound;
        }
        Tag<?, ?> regionsTag = root.getValue().get("Regions");
        if (!(regionsTag instanceof CompoundTag regions) || regions.getValue().isEmpty()) {
            throw new IOException("no regions in " + file.getName());
        }
        List<RegionBlocks> parts = new ArrayList<>();
        for (Tag<?, ?> regionTag : regions.getValue().values()) {
            if (regionTag instanceof CompoundTag region) {
                parts.add(readRegion(region));
            }
        }
        return parts;
    }

    private static RegionBlocks readRegion(CompoundTag region) throws IOException {
        int[] pos = intTriple(region, "Position");
        int[] size = intTriple(region, "Size");
        // Negative dimensions contain blocks in [size + 1; 0]: shift the
        // origin and work with absolute extents.
        int ox = pos[0], oy = pos[1], oz = pos[2];
        int sx = size[0], sy = size[1], sz = size[2];
        if (sx < 0) { ox += sx + 1; sx = -sx; }
        if (sy < 0) { oy += sy + 1; sy = -sy; }
        if (sz < 0) { oz += sz + 1; sz = -sz; }
        if (sx <= 0 || sy <= 0 || sz <= 0) {
            throw new IOException("region with non-positive size");
        }
        long total = (long) sx * sy * sz;
        if (total > 32_000_000L) {
            throw new IOException("region too large: " + total + " blocks");
        }

        List<String> palette = new ArrayList<>();
        for (CompoundTag state : region.getList("BlockStatePalette", CompoundTag.class)) {
            palette.add(blockName(state));
        }
        if (palette.isEmpty()) {
            throw new IOException("region with empty palette");
        }

        long[] packed = region.getLongArray("BlockStates");

        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        int perLong = 64 / bits;
        long mask = bits >= 64 ? -1L : (1L << bits) - 1L;
        String[] names = new String[(int) total];
        for (int i = 0; i < total; i++) {
            int li = i / perLong;
            int shift = (i % perLong) * bits;
            int index = li < packed.length ? (int) ((packed[li] >>> shift) & mask) : 0;
            names[i] = (index >= 0 && index < palette.size()) ? palette.get(index) : null;
        }
        return new RegionBlocks(ox, oy, oz, sx, sy, sz, names);
    }

    private static String blockName(CompoundTag state) {
        String name = state.getString("Name");
        if (name == null || name.isEmpty()) name = "minecraft:air";
        Tag<?, ?> propsTag = state.getValue().get("Properties");
        if (!(propsTag instanceof CompoundTag props) || props.getValue().isEmpty()) {
            return name;
        }
        StringBuilder out = new StringBuilder(name).append('[');
        boolean first = true;
        for (Map.Entry<String, Tag<?, ?>> e : props.getValue().entrySet()) {
            String v = e.getValue() instanceof CompoundTag ? null : String.valueOf(e.getValue().getValue());
            if (v == null) continue;
            if (!first) out.append(',');
            out.append(e.getKey()).append('=').append(v);
            first = false;
        }
        return out.append(']').toString();
    }

    private static int[] intTriple(CompoundTag parent, String key) throws IOException {
        Tag<?, ?> tag = parent.getValue().get(key);
        if (!(tag instanceof CompoundTag triple)) {
            throw new IOException("missing compound " + key);
        }
        return new int[]{ triple.getInt("x"), triple.getInt("y"), triple.getInt("z") };
    }
}
