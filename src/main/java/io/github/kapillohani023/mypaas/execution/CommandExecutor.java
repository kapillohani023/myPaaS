package io.github.kapillohani023.mypaas.execution;

/**
 * Blocking command execution seam. Production adapters run locally or over SSH;
 * the fake adapter is how the deployment engine is tested.
 */
public interface CommandExecutor {

    CommandResult run(ExecutionSpec spec, OutputSink output, CancellationToken cancellation);

    default CommandResult run(ExecutionSpec spec, OutputSink output) {
        return run(spec, output, CancellationToken.none());
    }
}
