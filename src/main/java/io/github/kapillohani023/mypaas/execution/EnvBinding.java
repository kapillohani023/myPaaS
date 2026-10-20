package io.github.kapillohani023.mypaas.execution;

/** An environment binding is either a literal or a credential reference. */
public record EnvBinding(Type type, String literalValue, Long credentialId) {

    public enum Type { LITERAL, CREDENTIAL }

    public static EnvBinding literal(String value) {
        return new EnvBinding(Type.LITERAL, value, null);
    }

    public static EnvBinding credential(long credentialId) {
        return new EnvBinding(Type.CREDENTIAL, null, credentialId);
    }
}
