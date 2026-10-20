package io.github.kapillohani023.mypaas.execution;

import org.springframework.stereotype.Component;

/** Selects the execution adapter from the target server record. */
@Component
public class CommandExecutorRouter {

    private final LocalCommandExecutor local;
    private final SshCommandExecutor ssh;

    public CommandExecutorRouter(LocalCommandExecutor local, SshCommandExecutor ssh) {
        this.local = local;
        this.ssh = ssh;
    }

    public CommandResult run(ExecutionSpec spec, OutputSink output) {
        return run(spec, output, CancellationToken.none());
    }

    public CommandResult run(ExecutionSpec spec, OutputSink output, CancellationToken cancellation) {
        return spec.target().local()
                ? local.run(spec, output, cancellation)
                : ssh.run(spec, output, cancellation);
    }
}
