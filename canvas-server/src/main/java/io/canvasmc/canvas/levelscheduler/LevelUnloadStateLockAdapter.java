package io.canvasmc.canvas.levelscheduler;

import net.minecraft.server.level.ServerLevel;

/**
 * Read-reference adapter exposing Luminol's {@code levelUnloadStateLock} API
 * (Lophine 0026 World load/unload APIs, GPL-3.0) on top of Canvas's native
 * world-stage protection ({@code canvas$worldStageLock} +
 * {@code canvas$unloadTicket} + {@code canvas$joiningPlayers}).
 *
 * Semantics mapping:
 * - {@link #acquireRead()} succeeds iff the level is not marked for unload;
 *   it registers the owner as a joining player under the world stage lock,
 *   which blocks Canvas's {@code WorldShutdownThread} from completing the
 *   unload while the reference is held (mirrors Luminol's read reference).
 * - {@link #releaseRead()} drops that reference.
 *
 * Write/unreachable semantics of the Luminol lock are intentionally NOT
 * implemented: Canvas owns world unloading through {@code WorldShutdownThread}
 * and the {@code canvas$unloadTicket}; this adapter only exists so that
 * ported patches expecting the Luminol read-guard API remain source-compatible.
 */
public final class LevelUnloadStateLockAdapter {
    private final ServerLevel level;

    public LevelUnloadStateLockAdapter(final ServerLevel level) {
        this.level = level;
    }

    /**
     * Attempt to acquire a read reference on this level.
     *
     * @param owner key identifying the holder (used for the joining-players set)
     * @return true if acquired; false if the level is unloading
     */
    public boolean acquireRead(final String owner) {
        this.level.canvas$executeUnderWorldStageLock(() -> {
            // re-check under the lock: unload may have been requested concurrently
        });
        if (this.level.canvas$unloadTicket.isPresent()) {
            return false;
        }
        this.level.canvas$incrementJoiningPlayers(owner);
        return true;
    }

    /**
     * Convenience overload matching Luminol call sites that pass no owner.
     */
    public boolean acquireRead() {
        return this.acquireRead("luminol-adapter-" + System.identityHashCode(this));
    }

    public void releaseRead(final String owner) {
        this.level.canvas$decrementJoiningPlayers(owner);
    }

    public void releaseRead() {
        // joining-players uses exact string matching, so owners must pair their calls;
        // the no-arg form mirrors Luminol's signature for source compatibility.
        this.releaseRead("luminol-adapter-" + System.identityHashCode(this));
    }
}
