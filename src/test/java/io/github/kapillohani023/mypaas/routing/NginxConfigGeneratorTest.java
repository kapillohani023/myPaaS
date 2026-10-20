package io.github.kapillohani023.mypaas.routing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NginxConfigGeneratorTest {

    @Test
    void generatesTlsProxyAndAccessLogFromStructuredFields() {
        String config = NginxConfigGenerator.generate("myapp", List.of("myapp.example.com"),
                true, 7L, "/etc/mypaas/certs", 3000, true, "/var/log/mypaas");

        assertThat(config)
                .contains("server_name myapp.example.com")
                .contains("listen 443 ssl")
                .contains("ssl_certificate /etc/mypaas/certs/7/fullchain.pem")
                .contains("ssl_certificate_key /etc/mypaas/certs/7/privkey.pem")
                .contains("proxy_pass http://127.0.0.1:3000")
                .contains("access_log /var/log/mypaas/myapp.access.log mypaas_access")
                .contains("return 301 https://$host$request_uri");
    }

    @Test
    void doesNotEmitTlsDirectivesWhenHttpsIsOff() {
        String config = NginxConfigGenerator.generate("myapp", List.of("myapp.example.com"),
                false, null, "/etc/mypaas/certs", 3000, false, "/var/log/mypaas");
        assertThat(config).doesNotContain("ssl_certificate").doesNotContain("listen 443");
    }

    @Test
    void certificateCoverageHandlesExactAndWildcardNames() {
        assertThat(CertificateParser.covers(List.of("myapp.example.com"), "myapp.example.com")).isTrue();
        assertThat(CertificateParser.covers(List.of("*.example.com"), "myapp.example.com")).isTrue();
        assertThat(CertificateParser.covers(List.of("*.example.com"), "deep.myapp.example.com")).isFalse();
        assertThat(CertificateParser.covers(List.of("other.example.com"), "myapp.example.com")).isFalse();
    }
}
