package io.github.kapillohani023.mypaas;

import io.github.kapillohani023.mypaas.core.Json;
import io.github.kapillohani023.mypaas.server.ServerCapabilities;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.Statement;
import java.util.List;

/** Direct fixtures for tests; services are exercised through their public interfaces. */
public final class Fixtures {

    private Fixtures() {
    }

    public static long user(JdbcTemplate jdbc, PasswordEncoder encoder, String email, String role) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    insert into users (email, display_name, password_hash, role, email_verified, created_at)
                    values (?, ?, ?, ?, true, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, email);
            ps.setString(2, email.substring(0, email.indexOf('@')));
            ps.setString(3, encoder.encode("test-password-123"));
            ps.setString(4, role);
            ps.setLong(5, System.currentTimeMillis());
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public static long server(JdbcTemplate jdbc, String name) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    insert into servers (name, host, ssh_port, username, local, os, capabilities,
                                         verified_at, created_at)
                    values (?, '127.0.0.1', 22, 'mypaas', true, 'Ubuntu 22.04', ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, Json.write(ServerCapabilities.demo("/opt/apps")));
            ps.setLong(3, System.currentTimeMillis());
            ps.setLong(4, System.currentTimeMillis());
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public static long project(JdbcTemplate jdbc, String name, long serverId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    insert into projects (name, slug, description, server_id, tracking_key, created_at, updated_at)
                    values (?, ?, 'test project', ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, name.toLowerCase().replace(' ', '-'));
            ps.setLong(3, serverId);
            ps.setString(4, java.util.UUID.randomUUID().toString().replace("-", ""));
            ps.setLong(5, System.currentTimeMillis());
            ps.setLong(6, System.currentTimeMillis());
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public static long file(JdbcTemplate jdbc, long projectId, String name) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    insert into stored_files (storage_key, original_name, content_type, size_bytes,
                                              checksum_sha256, sensitive, project_id, created_at)
                    values (?, ?, 'text/plain', 10, ?, false, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, "ab/cd/" + java.util.UUID.randomUUID());
            ps.setString(2, name);
            ps.setString(3, "a".repeat(64));
            ps.setLong(4, projectId);
            ps.setLong(5, System.currentTimeMillis());
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public static void member(JdbcTemplate jdbc, long projectId, long userId) {
        jdbc.update("insert into project_members (project_id, user_id) values (?, ?)", projectId, userId);
    }

    public static List<String> table(String... names) {
        return List.of(names);
    }
}
