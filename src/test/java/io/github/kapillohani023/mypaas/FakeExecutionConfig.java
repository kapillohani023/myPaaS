package io.github.kapillohani023.mypaas;

import io.github.kapillohani023.mypaas.execution.CancellationToken;
import io.github.kapillohani023.mypaas.execution.CommandExecutorRouter;
import io.github.kapillohani023.mypaas.execution.CommandResult;
import io.github.kapillohani023.mypaas.execution.ExecutionSpec;
import io.github.kapillohani023.mypaas.execution.OutputSink;
import io.github.kapillohani023.mypaas.storage.FileTransferRouter;
import io.github.kapillohani023.mypaas.storage.LocalFileTransfer;
import io.github.kapillohani023.mypaas.storage.SshFileTransfer;
import io.github.kapillohani023.mypaas.storage.TransferReceipt;
import io.github.kapillohani023.mypaas.storage.TransferSpec;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Fake execution and transfer adapters: the deployment engine's real test surface. */
@TestConfiguration
public class FakeExecutionConfig {

    public static final class CommandRecorder {
        public final List<String> commands = new CopyOnWriteArrayList<>();
        public volatile String failPrefix;
    }

    public static final class TransferRecorder {
        public final List<String> destinations = new CopyOnWriteArrayList<>();
    }

    @Bean
    public CommandRecorder commandRecorder() {
        return new CommandRecorder();
    }

    @Bean
    public TransferRecorder transferRecorder() {
        return new TransferRecorder();
    }

    @Bean
    @Primary
    public CommandExecutorRouter commandExecutorRouter(CommandRecorder recorder) {
        return new CommandExecutorRouter(null, null) {
            @Override
            public CommandResult run(ExecutionSpec spec, OutputSink output, CancellationToken cancellation) {
                String joined = String.join(" ", spec.argv());
                recorder.commands.add(joined);
                if (recorder.failPrefix != null && joined.startsWith(recorder.failPrefix)) {
                    return new CommandResult(1, false, false, false, Duration.ofMillis(1));
                }
                if (spec.argv().size() >= 2 && spec.argv().get(0).equals("test")
                        && spec.argv().get(1).equals("-f")) {
                    return new CommandResult(1, false, false, false, Duration.ofMillis(1));
                }
                if (spec.argv().contains("rev-parse")) {
                    output.line(OutputSink.Stream.STDOUT, "abcdef1234567890");
                } else if (spec.argv().contains("is-active")) {
                    output.line(OutputSink.Stream.STDOUT, "active");
                } else if (spec.argv().contains("curl")) {
                    output.line(OutputSink.Stream.STDOUT, "200");
                }
                return new CommandResult(0, false, false, false, Duration.ofMillis(1));
            }
        };
    }

    @Bean
    @Primary
    public FileTransferRouter fileTransferRouter(TransferRecorder recorder) {
        return new FileTransferRouter(new LocalFileTransfer(), new SshFileTransfer(null)) {
            @Override
            public TransferReceipt put(TransferSpec spec, CancellationToken cancellation) {
                recorder.destinations.add(spec.destination());
                return new TransferReceipt(spec.destination(), spec.checksumSha256(), spec.sizeBytes());
            }
        };
    }
}
