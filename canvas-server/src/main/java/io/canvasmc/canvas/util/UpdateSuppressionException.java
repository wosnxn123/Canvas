package io.canvasmc.canvas.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Thrown (and consumed) instead of crashing when an update suppression vector
 * (StackOverflowError / ClassCastException / IllegalArgumentException during physics
 * or block updates) is triggered while {@code catchUpdateSuppression} is enabled.
 *
 * Ported from LeavesMC/Leaves {@code org.leavesmc.leaves.util.UpdateSuppressionException}
 * (GPL-3.0, authors violetc & Bacteriawa), adapted: no Leaves event/logger dependencies.
 */
public class UpdateSuppressionException extends RuntimeException {
    private @Nullable BlockPos pos;
    private @Nullable Level level;
    private @Nullable Block source;
    private @Nullable ServerPlayer player;
    private final @NotNull Throwable throwable;

    public UpdateSuppressionException(
        @Nullable BlockPos pos,
        @Nullable Level level,
        @Nullable Block source,
        @Nullable ServerPlayer player,
        @NotNull Throwable throwable
    ) {
        super("Update Suppression");
        this.pos = pos;
        this.level = level;
        this.source = source;
        this.player = player;
        this.throwable = throwable;
    }

    public void providePlayer(@NotNull ServerPlayer player) {
        if (this.level == null) {
            this.level = player.level();
        }
        this.player = player;
    }

    public void provideLevel(@NotNull Level level) {
        if (this.level != null) {
            this.level = level;
        }
    }

    public void provideBlock(@NotNull Level level, @NotNull BlockPos pos, @NotNull Block source) {
        provideLevel(level);
        provideBlock(pos, source);
    }

    public void provideBlock(@NotNull BlockPos pos, @NotNull Block source) {
        if (this.pos != null) {
            this.pos = pos;
        }
        if (this.source != null) {
            this.source = source;
        }
    }

    public void consume() {
        net.minecraft.server.MinecraftServer.LOGGER.info(getMessage());
    }

    @Override
    public String getMessage() {
        List<String> messages = new ArrayList<>();
        messages.add("An %s update suppression was triggered".formatted(getTypeName()));
        if (source != null) {
            messages.add("from %s".formatted(source.defaultBlockState().getBukkitMaterial().name()));
        }
        if (pos != null) {
            messages.add("at [x:%d,y:%d,z:%d]".formatted(pos.getX(), pos.getY(), pos.getZ()));
        }
        if (level != null) {
            messages.add("in %s".formatted(level.dimension().identifier()));
        }
        if (player != null) {
            messages.add("by %s".formatted(player.displayName));
        }
        return String.join(" ", messages);
    }

    @Contract(pure = true)
    private @NotNull String getTypeName() {
        Class<? extends Throwable> type = throwable.getClass();
        if (type == ClassCastException.class) {
            return "CCE";
        } else if (type == StackOverflowError.class) {
            return "SOE";
        } else if (type == IllegalArgumentException.class) {
            return "IAE";
        }
        return type.getSimpleName();
    }

    @Override
    public Throwable fillInStackTrace() {
        return this;
    }

    @Override
    public StackTraceElement[] getStackTrace() {
        return this.throwable.getStackTrace(); // delegate to throwable as we don't have a stacktrace
    }
}
