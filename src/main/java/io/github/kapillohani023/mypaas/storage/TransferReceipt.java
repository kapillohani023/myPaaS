package io.github.kapillohani023.mypaas.storage;

public record TransferReceipt(String destination, String checksumSha256, long sizeBytes) {
}
