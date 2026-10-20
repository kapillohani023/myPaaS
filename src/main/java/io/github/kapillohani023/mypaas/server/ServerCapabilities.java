package io.github.kapillohani023.mypaas.server;

/** Cached capability detection for a managed server. */
public record ServerCapabilities(
        boolean git,
        boolean systemd,
        boolean nginx,
        boolean docker,
        String os,
        String arch,
        String allowedRoot,
        String systemRoot
) {
    public static ServerCapabilities demo(String allowedRoot) {
        return new ServerCapabilities(true, true, true, false, "Ubuntu 22.04", "x86_64",
                allowedRoot, "/etc/mypaas");
    }
}
