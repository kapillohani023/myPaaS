package io.github.kapillohani023.mypaas.security;

public record ResourceRef(ResourceType type, Long id) {

    public static ResourceRef global() {
        return new ResourceRef(ResourceType.GLOBAL, null);
    }

    public static ResourceRef project(long id) {
        return new ResourceRef(ResourceType.PROJECT, id);
    }

    public static ResourceRef server(long id) {
        return new ResourceRef(ResourceType.SERVER, id);
    }

    public static ResourceRef deployment(long id) {
        return new ResourceRef(ResourceType.DEPLOYMENT, id);
    }

    public static ResourceRef file(long id) {
        return new ResourceRef(ResourceType.FILE, id);
    }

    public static ResourceRef certificate(long id) {
        return new ResourceRef(ResourceType.CERTIFICATE, id);
    }
}
