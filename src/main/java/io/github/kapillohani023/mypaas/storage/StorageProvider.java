package io.github.kapillohani023.mypaas.storage;

import java.io.InputStream;
import java.nio.file.Path;

/** Internal storage adapter. Never exposed through a public API. */
public interface StorageProvider {

    void put(String storageKey, Path source, boolean sensitive);

    InputStream open(String storageKey, boolean sensitive);

    void delete(String storageKey);

    boolean exists(String storageKey);

    /** Re-wraps the file's data key to the current KEK version without decrypting content. */
    void rewrap(String storageKey);
}
