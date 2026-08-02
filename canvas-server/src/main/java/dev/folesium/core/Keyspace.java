/*
 * Folesium
 * Copyright (C) 2026 Folesium contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.folesium.core;

import dev.folesium.core.index.DictionaryStore;
import dev.folesium.core.index.PageIndex;
import dev.folesium.core.shard.ShardFile;
import dev.folesium.core.util.Bytes;
import dev.folesium.core.util.LongKeys;
import dev.folesium.core.util.UuidKeys;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * A named, sharded key-value namespace (e.g. {@code chunks}, {@code entities},
 * {@code poi}, {@code playerdata}).
 *
 * <p>Keys are routed to shards by {@link Bytes#mix64(byte[])}, so a given key
 * always maps to exactly one shard. Operations on distinct shards proceed fully
 * in parallel; there is no keyspace-wide lock on the hot path.</p>
 */
public final class Keyspace implements AutoCloseable {
    private final String name;
    /** Whether this keyspace was opened read-only: nothing may be written or compacted. */
    private final boolean readOnly;
    /**
     * Set by {@link #close()} before any shard is torn down; the maintenance passes
     * guard on it so they never touch shards close() is closing (same contract as
     * {@link FolesiumDatabase#compactIfNeeded(boolean)}).
     */
    private volatile boolean closed;
    /**
     * One slot per shard index of the on-disk layout, in routing order. Read-write
     * keyspaces eagerly open every shard of {@link FolesiumConfig#shardCount()};
     * read-only keyspaces size the array from the shard count recorded in the shard file
     * headers on disk (see {@link #readRecordedShardCount}: disagreeing readable headers
     * resolve to the count a majority of the readable headers record - the layout the swap
     * is converging to - and the names-derived fallback fires only when every discovered
     * header is unreadable) and
     * leave the slot of a
     * missing shard {@code null}. Fixed at construction and never mutated.
     */
    private final ShardFile[] shards;
    /**
     * Dense, non-null view of {@link #shards} in routing order (identical to
     * {@code shards} for read-write keyspaces, where every slot is open). The
     * iteration and maintenance paths walk this array, so an absent read-only shard is
     * simply skipped instead of crashing on a null slot.
     */
    private final ShardFile[] liveShards;
    private final int shardMask;
    /**
     * Region-page index for this keyspace, or {@code null} for non-region-keyed keyspaces
     * (playerdata/advancements/stats/misc), when {@code indexCacheBytes == 0} disables it,
     * or when the open failed before it could be created. Owned by this keyspace: closed
     * in {@link #close()} and shared by every shard.
     */
    private final PageIndex pageIndex;
    /**
     * Immutable per-keyspace dictionary for codec-3 (ZSTD_DICT) records, or {@code null} when
     * the keyspace is not region-keyed or no {@code <store>/idx/<name>/dict.bin} exists.
     * Loaded once at open whenever the dictionary file is present - also in read-only mode and
     * even when dictionary compression is currently disabled, because existing codec-3 records
     * cannot be decoded without it (whether new writes use the dictionary is decided per record
     * by {@link FolesiumConfig#dictionaryCompression()} in {@link ShardFile}). Shared by every
     * shard. A corrupt dictionary fails the open with a clear {@link FolesiumException}.
     *
     * <p>Training is deliberately not part of the open path: a keyspace whose dictionary file
     * is missing opens with {@code null} here, and reads of any codec-3 record then fail
     * loudly - the intended "missing dictionary" semantic. The dictionary is minted by the
     * conversion pipeline after a successful conversion (see
     * {@code dev.folesium.converter.WorldConverter}) and by tests via {@link DictionaryStore#train};
     * the resharder forwards this field to staged shard files, keeping rewrites consistent.</p>
     */
    private final byte[] keyspaceDict;

    Keyspace(Path dir, String name, FolesiumConfig config, boolean readOnly) {
        this.name = name;
        this.readOnly = readOnly;
        int[] discovered = readOnly ? discoveredShardIndices(dir, name) : null;
        int shardCount;
        if (readOnly) {
            if (discovered.length == 0) {
                shardCount = 0;
            } else {
                // Routing must match the power-of-two mask the store was written with,
                // not the count of files found: a sparse or torn layout (missing shard
                // files) must still route every present file to itself. The shard file
                // header records the authoritative shard count; absent slots stay null.
                shardCount = readRecordedShardCount(dir, name, discovered);
            }
        } else {
            shardCount = config.shardCount();
        }
        this.shards = new ShardFile[shardCount];
        this.shardMask = shardCount - 1;
        this.pageIndex = createPageIndex(dir, name, config, readOnly);
        byte[] dict;
        try {
            dict = loadKeyspaceDict(dir, name);
            // Read-only opens must validate every shard header against the topology the
            // file headers actually record ({@link #readRecordedShardCount}), not against
            // the current configuration: a config/metadata shard count that no longer
            // matches the physical files (e.g. a reshard interrupted between the file swap
            // and the metadata rewrite) must not fail a read-only open - the whole point
            // of the discovered-layout path is to open the store exactly as it lies on
            // disk. When no shard file exists (shardCount == 0) no ShardFile is
            // constructed, so the config is left alone.
            FolesiumConfig shardConfig = readOnly && shardCount > 0 ? config.withShardCount(shardCount) : config;
            for (int i = 0; i < shards.length; i++) {
                if (readOnly && Arrays.binarySearch(discovered, i) < 0) {
                    // Read-only: no shard file exists for this index (a keyspace that was
                    // never written, or an old layout with fewer shards than the current
                    // configuration expects). Read-only shards must never create the file,
                    // so leave the slot null: reads treat the shard as absent data, and the
                    // iteration/maintenance paths skip it (see {@link #liveShards}).
                    continue;
                }
                String shardName = String.format("%s-%04d", name, i);
                shards[i] = new ShardFile(dir.resolve(shardName + ".flog"), i, shardConfig, pageIndex,
                        shardName, shardConfig.indexMode() == FolesiumConfig.IndexMode.PAGE, dict, readOnly);
            }
        } catch (RuntimeException e) {
            // One bad shard must not leak the handles of the shards already opened: nobody
            // holds a reference to this half-built keyspace, so nothing else can close them.
            for (ShardFile s : shards) {
                if (s != null) {
                    try {
                        s.close();
                    } catch (RuntimeException suppressed) {
                        e.addSuppressed(suppressed);
                    }
                }
            }
            if (pageIndex != null) {
                try {
                    pageIndex.close();
                } catch (RuntimeException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            throw e;
        }
        this.keyspaceDict = dict;
        this.liveShards = readOnly
                ? Arrays.stream(shards).filter(Objects::nonNull).toArray(ShardFile[]::new)
                : shards;
        // The PageIndex damage-marker set is keyspace-global, while each shard's
        // open-time rebuild (ShardFile constructor) only replayed that shard's own log
        // range into the shared region pages - and a region's chunks hash across
        // shards. A single shard's clean rebuild therefore cannot prove every region
        // page complete: the previous per-shard clearAllDamage let a later shard's
        // clean build wipe the unresolved-region markers of an earlier shard, silently
        // losing PAGE-mode data for the affected regions. Clear the markers only when
        // every shard rebuilt without unresolved regions; if any shard left one (or
        // skipped its build - a torn/invalid header in a read-only open), keep every
        // marker so PAGE-mode reads keep falling back to the HashMap (conservative -
        // partial damage keeps all markers, and the next open retries the repair).
        if (pageIndex != null) {
            boolean anyUnresolved = false;
            for (ShardFile s : liveShards) {
                if (s.hasUnresolvedRegions()) {
                    anyUnresolved = true;
                    break;
                }
            }
            if (!anyUnresolved) {
                pageIndex.clearAllDamage();
            }
        }
    }

    /**
     * Indices of the shard files ({@code <name>-NNNN.flog}) present in {@code dir},
     * sorted ascending. Read-only opens discover the shard topology from disk instead of
     * trusting {@link FolesiumConfig#shardCount()}: the current configuration (or the
     * store metadata) may name more shards than the files actually written - an old
     * layout, or a keyspace that was never written to - and a read-only shard must never
     * create or touch a missing file. Files that do not belong to this keyspace (other
     * keyspaces, {@code .fidx} hints, {@code .tmp} scratch files) are ignored. Only
     * canonical names are accepted: the index part must be exactly four digits, matching
     * {@code StoreResharder.SHARD_FILE} and the {@code String.format("%s-%04d", name, i)}
     * names this keyspace itself opens. A non-canonical file (e.g. {@code chunks-12345.flog}
     * or {@code chunks-1.flog}) is skipped, so the discovered indices always match the
     * names the constructor and {@link #readRecordedShardCount} resolve ({@code %04d}) -
     * discovered and opened layouts stay identical.
     */
    private static int[] discoveredShardIndices(Path dir, String name) {
        Pattern shardFile = Pattern.compile("^" + Pattern.quote(name) + "-(\\d{4})\\.flog$");
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .flatMapToInt(fn -> {
                        Matcher m = shardFile.matcher(fn);
                        // Exactly four digits, so Integer.parseInt cannot overflow.
                        return m.matches() ? IntStream.of(Integer.parseInt(m.group(1))) : IntStream.empty();
                    })
                    .sorted()
                    .toArray();
        } catch (IOException e) {
            throw new FolesiumException("Cannot list " + dir + " to discover the shards of keyspace '"
                    + name + "'", e);
        }
    }

    /**
     * Reads the authoritative shard count from a shard file header (shard count at
     * offset 12, u32 big-endian, matching {@code ShardFile}'s file header). Validates the
     * header magic ({@code FLSM}) and format version (1) exactly like
     * {@code StoreResharder}'s header reader, so a foreign or garbage file matching the
     * {@code <name>-NNNN.flog} name pattern is never trusted; validates the shard index
     * against the index the file name implies (exactly like {@code ShardFile}'s
     * {@code validateFileHeader}), the power-of-two invariant so the read-only
     * routing mask is always legal, and that the index lies within the recorded count
     * (a header naming an index at or beyond its own count is internally inconsistent
     * and cannot belong to the layout it claims).
     *
     * <p>Torn-header tolerant: the count is store-wide and stamped into every shard file
     * header, so any intact shard names the layout. Every discovered file is tried in
     * ascending order; a file whose header is unreadable, fails the magic/version/index
     * check, or records an illegal count is skipped as untrusted - the lowest shard may
     * be torn (a crash truncated it before its header was ever written) or carry a
     * header that fails magic/version/topology validation; {@code ShardFile}'s read-only
     * construction tolerates both and treats such a shard as empty (a read-write open
     * would repair the torn case and reject the mismatched one loudly), so the skipped
     * file here and the empty shard there stay consistent while the higher shards are
     * intact. The count is only trusted once every <em>readable</em> header records the
     * same value: a reshard interrupted between the file swap and the metadata rewrite
     * can leave a mixture of old- and new-layout shard files, and taking the first
     * readable header's count would size the keyspace by chance. When the readable
     * headers disagree, the count that a <em>majority</em> of the readable headers
     * record wins: the swap rewrites the store one file at a time, so in the mixed
     * state most readable files already carry the layout being converged to, and
     * trusting it leaves only the minority of straggler files outside the routing mask -
     * the majority's visibility lower bound beats any fixed-index rule, which can hide
     * the majority when the swap has not reached that index yet (the names-derived
     * count below would reproduce the old layout's count in exactly that mixed state).
     * An exact tie resolves to the lowest-index readable header's count, keeping the
     * decision deterministic.
     * Only when <em>every</em> discovered header is unreadable does the count fall back
     * to the layout the file names imply: shard files are named by index under the
     * store's power-of-two shard count, so {@code highest index + 1} reproduces the
     * original count. This fallback is a tolerance path - the keyspace then opens with
     * an empty slot per unreadable shard instead of failing the whole keyspace - and is
     * only used when the derived count is itself a legal power of two (otherwise the
     * layout is genuinely unreadable and the failure is reported).
     */
    private static int readRecordedShardCount(Path dir, String name, int[] discovered) {
        RuntimeException lastFailure = null;
        Integer recorded = null;   // the count every readable header must agree on
        boolean conflicting = false; // two readable headers recorded different counts
        Integer firstReadableCount = null; // count of the lowest-index readable header (tie-break)
        Map<Integer, Integer> votes = new HashMap<>(); // count -> number of readable headers naming it
        Map<Integer, Integer> lowestIndexForCount = new HashMap<>(); // count -> lowest readable shard index naming it (explicit tie-break)
        for (int index : discovered) {
            Path shardFile = dir.resolve(String.format("%s-%04d.flog", name, index));
            try (FileChannel ch = FileChannel.open(shardFile, StandardOpenOption.READ)) {
                // Loop until the whole 16-byte header is read: a single read may legally
                // return fewer bytes than requested, so a one-shot read would misreport a
                // short (but complete-on-retry) read as a torn header. Only EOF before the
                // header is complete is the torn case - mirroring
                // StoreResharder.recordedShardCount.
                ByteBuffer header = ByteBuffer.allocate(16);
                while (header.hasRemaining()) {
                    if (ch.read(header) < 0) {
                        throw new FolesiumException("Shard file too short to read its header: " + shardFile);
                    }
                }
                header.flip();
                byte[] magic = new byte[4];
                header.get(magic);
                if (!Arrays.equals(magic, new byte[]{'F', 'L', 'S', 'M'}) || header.getShort() != 1) {
                    throw new FolesiumException(
                            "Not a Folesium shard file (bad magic or unsupported version): " + shardFile);
                }
                header.getShort(); // reserved
                int shardIndex = header.getInt();
                int count = header.getInt();
                // The header must name the shard its file name claims, exactly like
                // ShardFile.validateFileHeader: a file whose header records a different
                // index (e.g. a shard left over from an interrupted layout swap) is not
                // the shard it looks like and is not trusted.
                if (shardIndex != index) {
                    throw new FolesiumException("Shard topology mismatch in " + shardFile
                            + " (file: " + shardIndex + ", expected: " + index + ")");
                }
                if (count < 1 || count > 1024 || Integer.bitCount(count) != 1) {
                    throw new FolesiumException("Invalid shard count " + count + " in header of " + shardFile);
                }
                // The header's own index must lie inside the count it records: a legal
                // power-of-two count paired with an out-of-range index is internally
                // inconsistent (the shard could never be routed by that count's mask) and
                // is treated as untrusted like any other failed topology validation.
                if (shardIndex < 0 || shardIndex >= count) {
                    throw new FolesiumException("Shard header of " + shardFile + " records index "
                            + shardIndex + " outside its claimed shard count " + count);
                }
                if (firstReadableCount == null) {
                    // discovered is ascending, so the first readable header is the
                    // lowest-index one; its count is the deterministic tie-break when the
                    // readable headers split exactly 50/50.
                    firstReadableCount = count;
                }
                votes.merge(count, 1, Integer::sum);
                // Explicit tie-break bookkeeping: an exact tie between counts resolves to
                // the count recorded by the lowest-index readable header (see below), and
                // HashMap iteration order is not deterministic, so the lowest index per
                // count is tracked explicitly instead of being resolved by iteration order.
                lowestIndexForCount.merge(count, index, Math::min);
                // The count is store-wide and stamped into every header, so every readable
                // header must agree on it: a reshard interrupted between the file swap and
                // the metadata rewrite can leave a mixture of old- and new-layout shard
                // files, and trusting whichever header happens to be read first would size
                // the keyspace by chance. Any disagreement is resolved below via the
                // majority readable count, not by chance.
                if (recorded == null) {
                    recorded = count;
                } else if (recorded != count) {
                    conflicting = true;
                    lastFailure = new FolesiumException("Shard headers of keyspace '" + name + "' in " + dir
                            + " disagree on the shard count (read " + recorded + " and " + count + ")");
                }
            } catch (IOException e) {
                lastFailure = new FolesiumException("Cannot read shard header " + shardFile, e);
            } catch (FolesiumException e) {
                lastFailure = e;
            }
        }
        if (recorded != null && !conflicting) {
            return recorded;
        }
        if (conflicting) {
            // The readable headers disagree: a reshard interrupted between the file swap
            // and the metadata rewrite left old- and new-layout shard files mixed. The
            // swap rewrites the store one file at a time, so in the mixed state most
            // readable files already carry the layout being converged to; the count most
            // of them record is that layout, and trusting it hides only the minority of
            // stragglers from the other layout - a better visibility lower bound than any
            // fixed-index rule, which can hide the majority when the swap has not reached
            // that index yet (the names-derived fallback would reproduce the old layout's
            // count in exactly this state). An exact tie resolves to the lowest-index
            // readable header's count, the previous rule, keeping the decision
            // deterministic.
            int best = firstReadableCount;
            int bestVotes = votes.getOrDefault(best, 0);
            for (var entry : votes.entrySet()) {
                int candidate = entry.getKey();
                int candidateVotes = entry.getValue();
                // Ties resolve to the lowest-index readable header's count - the
                // deterministic rule the javadoc promises. votes is a HashMap whose
                // iteration order is not deterministic, so the tie-break uses the
                // explicitly tracked lowest recording index, never the map's order;
                // firstReadableCount (the global lowest readable header) wins any tie it
                // participates in, exactly as before.
                if (candidateVotes > bestVotes
                        || (candidateVotes == bestVotes
                                && lowestIndexForCount.get(candidate) < lowestIndexForCount.get(best))) {
                    best = candidate;
                    bestVotes = candidateVotes;
                }
            }
            return best;
        }
        // Every discovered header is unreadable (e.g. a crash truncated the whole lowest
        // shard before any header was written, or every header failed the
        // magic/version/index validation). Fall back to the names-derived count; see the
        // method javadoc for why this reproduces the original power-of-two layout.
        int byNames = discovered[discovered.length - 1] + 1;
        if (byNames >= 1 && byNames <= 1024 && Integer.bitCount(byNames) == 1) {
            return byNames;
        }
        throw new FolesiumException("Cannot read the shard header of any of the " + discovered.length
                + " discovered shard files of keyspace '" + name + "' in " + dir
                + " (all torn, unreadable or disagreeing)", lastFailure);
    }

    private static boolean isRegionKeyed(String name) {
        return FolesiumDatabase.KS_CHUNKS.equals(name)
                || FolesiumDatabase.KS_ENTITIES.equals(name)
                || FolesiumDatabase.KS_POI.equals(name);
    }

    /**
     * Creates the region-page index for a region-keyed keyspace ({@code chunks},
     * {@code entities}, {@code poi}) when the page index is enabled; {@code null}
     * otherwise. {@code readOnly} opens the index in memory only - no directory is
     * created and nothing is written to disk.
     */
    private static PageIndex createPageIndex(Path dir, String name, FolesiumConfig config, boolean readOnly) {
        if (config.indexCacheBytes() <= 0) {
            return null;
        }
        if (!isRegionKeyed(name)) {
            return null;
        }
        try {
            return new PageIndex(dir.resolve("idx").resolve(name), config.indexCacheBytes(), readOnly);
        } catch (IOException e) {
            throw new FolesiumException("Cannot open the page index of keyspace '" + name + "' in " + dir, e);
        }
    }

    /**
     * Loads the per-keyspace dictionary ({@code <store>/idx/<name>/dict.bin}) for a
     * region-keyed keyspace. The dictionary is objective data required to decode existing
     * codec-3 (ZSTD_DICT) records, so it is loaded whenever the file exists - regardless of
     * whether dictionary compression is currently enabled (the write path decides per record
     * in {@link ShardFile}, gated on {@link FolesiumConfig#dictionaryCompression()}). Missing
     * dictionary means no codec-3 record can exist yet, so {@code null} (plain compression) is
     * correct. A corrupt or unreadable dictionary fails the open: codec-3 records would be
     * undecodable. Also loaded in read-only mode, where reads of codec-3 records still need it.
     */
    private static byte[] loadKeyspaceDict(Path dir, String name) {
        if (!isRegionKeyed(name)) {
            return null;
        }
        Path dictFile = dir.resolve("idx").resolve(name).resolve("dict.bin");
        if (!Files.exists(dictFile)) {
            return null;
        }
        try {
            return DictionaryStore.load(dictFile);
        } catch (FolesiumException e) {
            throw new FolesiumException("Dictionary of keyspace '" + name + "' in " + dictFile
                    + " is corrupt; codec-3 (ZSTD_DICT) records cannot be read without it. "
                    + "Restore dict.bin from a backup, or delete it and re-run the conversion "
                    + "(dictionary compression must be re-enabled to write codec-3 records).", e);
        } catch (IOException e) {
            throw new FolesiumException("Cannot load the dictionary of keyspace '" + name + "' from " + dictFile, e);
        }
    }

    public String name() {
        return name;
    }

    /**
     * Number of shard slots in this keyspace's layout. Read-write keyspaces return
     * {@link FolesiumConfig#shardCount()}; read-only keyspaces return the shard count
     * recorded in the on-disk shard file headers - the authoritative physical topology,
     * with the names-derived count used only when every discovered header is unreadable,
     * and {@code 0} when no shard file exists. Slots whose file is missing on disk are
     * {@code null} (see {@link #shards}); routing via {@link #shardIndexFor(byte[])}
     * never exceeds this count.
     */
    public int shardCount() {
        return shards.length;
    }

    /**
     * The shards of this keyspace, in routing order, with absent read-only shards (no
     * file on disk) omitted. Package-private: the database reads it to collect
     * compaction candidates across keyspaces for workload-ordered compaction. The array
     * is fixed at construction and never mutated; callers must not modify it.
     */
    ShardFile[] shards() {
        return liveShards;
    }

    /**
     * Pushes a new runtime configuration to every shard. Takes effect on the next
     * operation; nothing on disk is touched.
     *
     * @throws IllegalArgumentException if the shard count differs from this keyspace's
     *                                  physical topology
     */
    public void applyRuntimeConfig(FolesiumConfig next) {
        if (next.shardCount() != shards.length) {
            throw new IllegalArgumentException("Cannot change shardCount on the open keyspace '"
                    + name + "' (" + shards.length + " -> " + next.shardCount() + ")");
        }
        for (ShardFile s : liveShards) {
            s.applyRuntimeConfig(next);
        }
    }

    private ShardFile shardFor(byte[] key) {
        if (shards.length == 0) {
            // No shard files exist at all (an empty read-only store): every key is absent.
            return null;
        }
        return shards[(int) (Bytes.mix64(key) & shardMask)];
    }

    /**
     * The shard index of {@code key} under this keyspace's routing mask, or {@code -1}
     * when the keyspace has no shards at all (an empty read-only store).
     */
    public int shardIndexFor(byte[] key) {
        if (shards.length == 0) {
            return -1;
        }
        return (int) (Bytes.mix64(key) & shardMask);
    }

    // ------------------------------------------------------------- byte[] API

    public byte[] get(byte[] key) {
        Objects.requireNonNull(key, "key");
        ShardFile shard = shardFor(key);
        return shard == null ? null : shard.get(new Bytes(key));
    }

    public boolean contains(byte[] key) {
        Objects.requireNonNull(key, "key");
        ShardFile shard = shardFor(key);
        return shard != null && shard.contains(new Bytes(key));
    }

    public void put(byte[] key, byte[] value) {
        Objects.requireNonNull(key, "key");
        if (value == null) {
            throw new IllegalArgumentException("null value; use delete()");
        }
        requireShardForWrite(key).put(new Bytes(key), value);
    }

    /** Stores the value only if the key is absent; returns {@code true} if written. */
    public boolean putIfAbsent(byte[] key, byte[] value) {
        Objects.requireNonNull(key, "key");
        if (value == null) {
            throw new IllegalArgumentException("null value; use delete()");
        }
        return requireShardForWrite(key).putIfAbsent(new Bytes(key), value);
    }

    public void delete(byte[] key) {
        Objects.requireNonNull(key, "key");
        requireShardForWrite(key).delete(new Bytes(key));
    }

    /**
     * The shard owning {@code key}, failing loudly when no shard exists for it. A null
     * slot means the key's shard file is absent from disk - only possible in a
     * read-only keyspace (read-write keyspaces eagerly open every shard), where writes
     * must never be silently dropped. The read paths ({@link #get}, {@link #contains})
     * treat the same situation as absent data instead.
     */
    private ShardFile requireShardForWrite(byte[] key) {
        ShardFile shard = shardFor(key);
        if (shard == null) {
            throw new IllegalStateException("Cannot write in read-only keyspace '" + name
                    + "': the key's shard file is missing from disk");
        }
        return shard;
    }

    // --------------------------------------------------------- chunk-key API

    public byte[] get(long chunkKey) {
        return get(LongKeys.encode(chunkKey));
    }

    public void put(long chunkKey, byte[] value) {
        put(LongKeys.encode(chunkKey), value);
    }

    /** Stores the value only if the chunk key is absent; returns {@code true} if written. */
    public boolean putIfAbsent(long chunkKey, byte[] value) {
        return putIfAbsent(LongKeys.encode(chunkKey), value);
    }

    public void delete(long chunkKey) {
        delete(LongKeys.encode(chunkKey));
    }

    public boolean contains(long chunkKey) {
        return contains(LongKeys.encode(chunkKey));
    }

    // ---------------------------------------------------------- player-key API

    public byte[] get(UUID player) {
        return get(UuidKeys.encode(player));
    }

    public void put(UUID player, byte[] value) {
        put(UuidKeys.encode(player), value);
    }

    /** Stores the value only if the player key is absent; returns {@code true} if written. */
    public boolean putIfAbsent(UUID player, byte[] value) {
        return putIfAbsent(UuidKeys.encode(player), value);
    }

    public void delete(UUID player) {
        delete(UuidKeys.encode(player));
    }

    public boolean contains(UUID player) {
        return contains(UuidKeys.encode(player));
    }

    // ------------------------------------------------------------ maintenance

    public void forEach(BiConsumer<byte[], byte[]> consumer) {
        for (ShardFile s : liveShards) {
            s.forEach(consumer);
        }
    }

    /**
     * Iterates every live key without reading any value - much cheaper than {@link #forEach}
     * when only the key set is needed, since no record is read back or decompressed.
     */
    public void forEachKey(java.util.function.Consumer<byte[]> consumer) {
        for (ShardFile s : liveShards) {
            s.forEachKey(consumer);
        }
    }

    /**
     * Iterates one shard only; lets callers parallelise a full scan safely. A shard
     * absent from disk in a read-only keyspace is skipped.
     */
    public void forEachShard(int shardIndex, BiConsumer<byte[], byte[]> consumer) {
        if (shardIndex < 0 || shardIndex >= shards.length) {
            throw new IndexOutOfBoundsException("shardIndex " + shardIndex + " outside [0,"
                    + shards.length + ") of keyspace '" + name + "'");
        }
        ShardFile shard = shards[shardIndex];
        if (shard != null) {
            shard.forEach(consumer);
        }
    }

    public void flush() {
        for (ShardFile s : liveShards) {
            s.flushIfDirty();
        }
        // Log-first, best-effort: dirty pages must not be persisted ahead of the log data
        // they reference, so the shard forces run first. This is an ordering preference,
        // not the correctness mechanism - a concurrent writer can dirty a shard after its
        // flushIfDirty() above (the two phases take different locks), and flushIfDirty()
        // early-returns with dirty still set when the shard channel is closed, so the page
        // flush can still outrun the log force. Correctness is enforced by the read-path
        // slot trimming instead: a page persisted ahead of its log data holds slots at or
        // past the log EOF, which pageIndexLoc rejects as misses (and the record key must
        // match the slot's key), so such a page reads as absent until the log catches up.
        if (pageIndex != null) {
            pageIndex.flush();
        }
    }

    public void compactIfNeeded() {
        if (closed || readOnly) {
            return; // do not touch shards close() is tearing down, or a read-only store
        }
        for (ShardFile s : liveShards) {
            if (s.needsCompaction()) {
                s.compact();
            }
        }
    }

    public void compactAll() {
        if (closed || readOnly) {
            return; // do not touch shards close() is tearing down, or a read-only store
        }
        for (ShardFile s : liveShards) {
            s.compact();
        }
    }

    public long count() {
        long n = 0;
        for (ShardFile s : liveShards) {
            n += s.count();
        }
        return n;
    }

    public long sizeBytes() {
        long n = 0;
        for (ShardFile s : liveShards) {
            n += s.sizeBytes();
        }
        return n;
    }

    public long deadBytes() {
        long n = 0;
        for (ShardFile s : liveShards) {
            n += s.deadBytes();
        }
        return n;
    }

    /**
     * The region-page index of this keyspace, or {@code null} when pages are disabled or
     * the keyspace is not region-keyed. Exposed for tests and tooling.
     */
    public PageIndex pageIndex() {
        return pageIndex;
    }

    /**
     * The per-keyspace codec-3 (ZSTD_DICT) dictionary of this keyspace, or {@code null} when
     * none is loaded. Package-private: the resharder forwards it to the staged shard files so
     * the rewritten records keep using the same trained dictionary.
     */
    byte[] keyspaceDict() {
        return keyspaceDict;
    }

    /**
     * Persists every pending per-shard watermark ({@code <shardName>.wmk} files) of this
     * keyspace's page index. Called by the checkpoint path after the shard logs were
     * forced and the dirty pages flushed, so a watermark never claims log data that is
     * not yet durable. No-op without a page index or in read-only mode.
     */
    public void flushWatermarks() {
        if (pageIndex == null) {
            return;
        }
        try {
            pageIndex.flushWatermarks();
        } catch (IOException e) {
            throw new FolesiumException("Cannot flush shard watermarks of keyspace '" + name + "'", e);
        }
    }

    @Override
    public void close() {
        closed = true;
        FolesiumException first = null;
        for (ShardFile s : liveShards) {
            try {
                s.close();
            } catch (FolesiumException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (pageIndex != null) {
            // After the shards: page flush must never outrun the log force in close().
            // Order within the index: dirty pages first, then the watermarks (a watermark must
            // never claim log offsets that the pages on disk do not yet cover), then close()
            // writes the hint manifest and releases the files. Each phase is guarded separately
            // so a failed flush()/flushWatermarks() can never skip pageIndex.close(): skipping
            // it would leak file handles and leave a stale hint manifest on disk.
            try {
                pageIndex.flush();
            } catch (RuntimeException e) {
                first = recordPageIndexFailure(first, "flush dirty pages", e);
            }
            try {
                pageIndex.flushWatermarks();
            } catch (IOException | RuntimeException e) {
                first = recordPageIndexFailure(first, "flush shard watermarks", e);
            }
            try {
                pageIndex.close();
            } catch (RuntimeException e) {
                first = recordPageIndexFailure(first, "close the page index", e);
            }
        }
        if (first != null) {
            throw first;
        }
    }

    /**
     * Records a page-index phase failure from {@link #close()} and returns the (possibly new)
     * first failure, so every phase failure surfaces instead of being lost to the last one.
     */
    private FolesiumException recordPageIndexFailure(FolesiumException first, String phase, Exception e) {
        FolesiumException failure = e instanceof FolesiumException fe
                ? fe : new FolesiumException("Cannot " + phase + " of keyspace '" + name + "'", e);
        if (first == null) {
            return failure;
        }
        first.addSuppressed(failure);
        return first;
    }
}
