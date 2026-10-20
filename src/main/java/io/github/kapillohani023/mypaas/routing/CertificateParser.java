package io.github.kapillohani023.mypaas.routing;

import io.github.kapillohani023.mypaas.core.ValidationException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

/** Validates certificate chains, private-key match, validity and hostname coverage. */
public final class CertificateParser {

    private static final Pattern PEM_CERT = Pattern.compile(
            "-----BEGIN CERTIFICATE-----([\\s\\S]*?)-----END CERTIFICATE-----");
    private static final Pattern PEM_KEY = Pattern.compile(
            "-----BEGIN (?:PRIVATE KEY|RSA PRIVATE KEY|EC PRIVATE KEY)-----([\\s\\S]*?)-----END");

    private CertificateParser() {
    }

    public record Parsed(
            X509Certificate leaf,
            List<X509Certificate> chain,
            PrivateKey privateKey,
            List<String> hostnames,
            long notBefore,
            long notAfter
    ) {
    }

    public static Parsed parse(CertificateUpload upload, Clock clock) {
        List<String> errors = new ArrayList<>();
        List<X509Certificate> chain = parseChain(upload.chainPem(), errors);
        if (chain.isEmpty()) {
            throw new ValidationException(errors.isEmpty()
                    ? List.of("A PEM certificate chain is required") : errors);
        }
        if (!upload.privateKeyPem().contains("BEGIN PRIVATE KEY")) {
            throw new ValidationException(
                    "Private key must be PKCS#8 (BEGIN PRIVATE KEY); convert legacy keys with openssl pkcs8");
        }
        PrivateKey privateKey = parsePrivateKey(upload.privateKeyPem(), errors);
        X509Certificate leaf = chain.getFirst();
        try {
            leaf.checkValidity(java.util.Date.from(clock.instant()));
        } catch (java.security.cert.CertificateExpiredException e) {
            errors.add("Certificate has expired");
        } catch (java.security.cert.CertificateNotYetValidException e) {
            errors.add("Certificate is not valid yet");
        }
        if (!keyMatches(leaf.getPublicKey(), privateKey)) {
            errors.add("Private key does not match the certificate");
        }
        List<String> coverage = hostnames(leaf);
        if (coverage.isEmpty()) {
            errors.add("Certificate has no DNS subject alternative names");
        }
        for (String hostname : upload.hostnames()) {
            if (!covers(coverage, hostname)) {
                errors.add("Certificate does not cover hostname " + hostname);
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        return new Parsed(leaf, List.copyOf(chain), privateKey, coverage,
                leaf.getNotBefore().getTime(), leaf.getNotAfter().getTime());
    }

    private static List<X509Certificate> parseChain(String pem, List<String> errors) {
        if (pem == null || pem.isBlank()) {
            return List.of();
        }
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> parsed = factory.generateCertificates(
                    new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
            List<X509Certificate> result = new ArrayList<>();
            for (Certificate certificate : parsed) {
                result.add((X509Certificate) certificate);
            }
            if (result.isEmpty()) {
                errors.add("No certificates found in upload");
            }
            return result;
        } catch (Exception e) {
            errors.add("Certificate chain could not be parsed");
            return List.of();
        }
    }

    private static PrivateKey parsePrivateKey(String pem, List<String> errors) {
        try {
            Matcher matcher = PEM_KEY.matcher(pem);
            if (!matcher.find()) {
                errors.add("Private key could not be parsed");
                return null;
            }
            byte[] der = Base64.getMimeDecoder().decode(matcher.group(1));
            String algorithm = pem.contains("BEGIN EC PRIVATE KEY") ? "EC" : "RSA";
            return java.security.KeyFactory.getInstance(algorithm)
                    .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            errors.add("Private key could not be parsed");
            return null;
        }
    }

    private static boolean keyMatches(PublicKey publicKey, PrivateKey privateKey) {
        if (privateKey == null) {
            return false;
        }
        try {
            String algorithm = switch (privateKey.getAlgorithm()) {
                case "EC" -> "SHA256withECDSA";
                case "EdDSA", "Ed25519" -> "Ed25519";
                default -> "SHA256withRSA";
            };
            byte[] challenge = new byte[32];
            new java.security.SecureRandom().nextBytes(challenge);
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign(privateKey);
            signer.update(challenge);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(publicKey);
            verifier.update(challenge);
            return verifier.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    public static List<String> hostnames(X509Certificate certificate) {
        List<String> names = new ArrayList<>();
        try {
            Collection<List<?>> sans = certificate.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> san : sans) {
                    if (san.size() >= 2 && Integer.valueOf(2).equals(san.get(0))) {
                        names.add(String.valueOf(san.get(1)).toLowerCase(Locale.ROOT));
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall back to CN.
        }
        if (names.isEmpty()) {
            try {
                LdapName name = new LdapName(certificate.getSubjectX500Principal().getName());
                for (Rdn rdn : name.getRdns()) {
                    if ("CN".equalsIgnoreCase(rdn.getType())) {
                        names.add(String.valueOf(rdn.getValue()).toLowerCase(Locale.ROOT));
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return names;
    }

    public static boolean covers(List<String> certificateNames, String hostname) {
        String target = hostname.toLowerCase(Locale.ROOT);
        for (String name : certificateNames) {
            if (name.equals(target)) {
                return true;
            }
            if (name.startsWith("*.")) {
                String suffix = name.substring(1);
                if (target.endsWith(suffix)) {
                    String prefix = target.substring(0, target.length() - suffix.length());
                    if (!prefix.isEmpty() && !prefix.contains(".")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
