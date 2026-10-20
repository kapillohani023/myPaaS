package io.github.kapillohani023.mypaas.storage;

import java.io.InputStream;

/** Internal file-transfer seam behind {@code storage.transfer(...)}. */
public interface FileTransfer {

    boolean supports(TransferTarget target);

    TransferReceipt put(TransferSpec spec, io.github.kapillohani023.mypaas.execution.CancellationToken cancellation);
}
