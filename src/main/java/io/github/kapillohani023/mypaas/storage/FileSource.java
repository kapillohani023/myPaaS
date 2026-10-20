package io.github.kapillohani023.mypaas.storage;

import java.io.IOException;
import java.io.InputStream;

@FunctionalInterface
public interface FileSource {
    InputStream open() throws IOException;
}
