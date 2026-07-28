package io.canvasmc.canvas.subcommands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.canvasmc.canvas.commands.SubCommand;
import io.canvasmc.canvas.storage.cesium.CesiumStorageManager;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import static net.minecraft.commands.Commands.literal;

/** Read-only operational status for the Cesium storage engine. */
public final class CesiumStatusSubCommand implements SubCommand {
    @Override
    public String getDescription() {
        return "Shows Cesium backend identity, lifecycle, queue, and failure status.";
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> construct(
        final LiteralArgumentBuilder<CommandSourceStack> base,
        final CommandBuildContext buildContext
    ) {
        return base.executes(context -> show(context.getSource()))
            .then(literal("status").executes(context -> show(context.getSource())));
    }

    @Override
    public String getName() {
        return "cesium";
    }

    private static int show(final CommandSourceStack source) {
        final CesiumStorageManager.Status status = CesiumStorageManager.current().status();
        source.sendSuccess(() -> Component.literal("Cesium: enabled=" + status.enabled()
            + " backend=" + status.backend() + " state=" + status.state()
            + (status.path() == null ? "" : " path=" + status.path())), false);
        sendScope(source, "global", status.global());
        sendScope(source, "dimensions", status.dimensions());
        if (status.terminalFailure() != null) {
            source.sendFailure(Component.literal("Cesium terminal failure: " + status.terminalFailure()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static void sendScope(final CommandSourceStack source, final String name,
                                  final CesiumStorageManager.@Nullable ScopeStatus scope) {
        if (scope == null) return;
        source.sendSuccess(() -> Component.literal("Cesium " + name
            + ": schema=" + scope.schemaId()
            + " generation=" + scope.manifestGeneration()
            + " manifest=" + (scope.manifestClean() ? "clean" : "dirty")
            + " outstanding=" + scope.outstandingOperations() + " ops/" + scope.outstandingBytes() + " bytes"
            + " highWater=" + scope.highWaterOperations() + " ops/" + scope.highWaterBytes() + " bytes"
            + " commits=" + scope.successfulCommits()
            + " commitFailures=" + scope.commitFailures()
            + " flushFailures=" + scope.flushFailures()
            + " retries=" + scope.retries()
            + " backpressureEvents=" + scope.backpressureEvents()
            + " backpressured=" + scope.backpressured()
            + " acceptingWrites=" + scope.acceptingWrites()), false);
    }
}
