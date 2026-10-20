package io.github.kapillohani023.mypaas.routing;

import java.util.List;

/** Generates managed Nginx configuration from validated structured fields only. */
public final class NginxConfigGenerator {

    /** Owned by routing; analytics parses this exact format. */
    public static final String ACCESS_LOG_FORMAT =
            "$remote_addr - $remote_user [$time_local] \"$request_method $uri $server_protocol\" "
                    + "$status $body_bytes_sent \"$http_referer\" \"$http_user_agent\" $request_time";

    private NginxConfigGenerator() {
    }

    public static String generate(String slug, List<String> hostnames, boolean https,
                                  Long certificateId, String certRoot, int internalPort,
                                  boolean accessLogs, String logRoot) {
        StringBuilder config = new StringBuilder();
        config.append("# Managed by myPaaS. Do not edit by hand.\n");
        config.append("log_format mypaas_access '").append(ACCESS_LOG_FORMAT).append("';\n\n");
        for (String hostname : hostnames) {
            if (https && certificateId != null) {
                config.append("server {\n")
                        .append("    listen 80;\n")
                        .append("    server_name ").append(hostname).append(";\n")
                        .append("    return 301 https://$host$request_uri;\n")
                        .append("}\n\n");
                config.append("server {\n")
                        .append("    listen 443 ssl;\n")
                        .append("    server_name ").append(hostname).append(";\n")
                        .append("    ssl_certificate ").append(certRoot).append('/')
                        .append(certificateId).append("/fullchain.pem;\n")
                        .append("    ssl_certificate_key ").append(certRoot).append('/')
                        .append(certificateId).append("/privkey.pem;\n")
                        .append("    ssl_protocols TLSv1.2 TLSv1.3;\n");
                if (accessLogs) {
                    config.append("    access_log ").append(logRoot).append('/').append(slug)
                            .append(".access.log mypaas_access;\n");
                }
                config.append(proxyBlock(internalPort))
                        .append("}\n\n");
            } else {
                config.append("server {\n")
                        .append("    listen 80;\n")
                        .append("    server_name ").append(hostname).append(";\n");
                if (accessLogs) {
                    config.append("    access_log ").append(logRoot).append('/').append(slug)
                            .append(".access.log mypaas_access;\n");
                }
                config.append(proxyBlock(internalPort))
                        .append("}\n\n");
            }
        }
        return config.toString();
    }

    private static String proxyBlock(int internalPort) {
        return """
                    location / {
                        proxy_pass http://127.0.0.1:%d;
                        proxy_http_version 1.1;
                        proxy_set_header Host $host;
                        proxy_set_header X-Real-IP $remote_addr;
                        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
                        proxy_set_header X-Forwarded-Proto $scheme;
                        proxy_set_header Upgrade $http_upgrade;
                        proxy_set_header Connection "upgrade";
                        proxy_read_timeout 300s;
                    }
                """.formatted(internalPort);
    }
}
