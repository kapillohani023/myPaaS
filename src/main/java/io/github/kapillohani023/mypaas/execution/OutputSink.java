package io.github.kapillohani023.mypaas.execution;

/** Blocking output seam. Implementations apply backpressure and redaction. */
@FunctionalInterface
public interface OutputSink {

    void line(Stream stream, String text);

    enum Stream { STDOUT, STDERR, SYSTEM }
}
