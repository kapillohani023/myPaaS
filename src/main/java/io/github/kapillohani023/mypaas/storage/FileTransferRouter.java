package io.github.kapillohani023.mypaas.storage;

import io.github.kapillohani023.mypaas.core.DomainException;
import io.github.kapillohani023.mypaas.execution.CancellationToken;
import org.springframework.stereotype.Component;

import java.util.List;

/** Chooses the FileTransfer adapter for a target server. */
@Component
public class FileTransferRouter implements FileTransfer {

    private final List<FileTransfer> adapters;

    public FileTransferRouter(LocalFileTransfer local, SshFileTransfer ssh) {
        this.adapters = List.of(local, ssh);
    }

    @Override
    public boolean supports(TransferTarget target) {
        return adapters.stream().anyMatch(a -> a.supports(target));
    }

    @Override
    public TransferReceipt put(TransferSpec spec, CancellationToken cancellation) {
        return adapters.stream()
                .filter(a -> a.supports(spec.target()))
                .findFirst()
                .orElseThrow(() -> new DomainException("transfer_unavailable", 500,
                        "No file-transfer adapter for target"))
                .put(spec, cancellation);
    }
}
