package io.github.kapillohani023.mypaas.project;

import io.github.kapillohani023.mypaas.audit.AuditOutcome;
import io.github.kapillohani023.mypaas.audit.AuditService;
import io.github.kapillohani023.mypaas.authorization.AccessScope;
import io.github.kapillohani023.mypaas.authorization.Authorization;
import io.github.kapillohani023.mypaas.config.MypaasProperties;
import io.github.kapillohani023.mypaas.core.ConflictException;
import io.github.kapillohani023.mypaas.core.Json;
import io.github.kapillohani023.mypaas.core.NotFoundException;
import io.github.kapillohani023.mypaas.core.PageRequest;
import io.github.kapillohani023.mypaas.core.PageResult;
import io.github.kapillohani023.mypaas.core.ValidationException;
import io.github.kapillohani023.mypaas.security.Action;
import io.github.kapillohani023.mypaas.security.ActorContext;
import io.github.kapillohani023.mypaas.security.ResourceRef;
import io.github.kapillohani023.mypaas.security.ResourceType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Project CRUD, static validation and the append-only configuration revision
 * sequence. Does not absorb deployment logic.
 */
@Service
public class ProjectService {

    private static final RowMapper<Project> PROJECT_MAPPER = (rs, rowNum) -> new Project(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("slug"),
            rs.getString("description"),
            rs.getLong("server_id"),
            rs.getObject("latest_revision_id", Long.class),
            rs.getString("tracking_key"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private final JdbcTemplate jdbc;
    private final Authorization authorization;
    private final AuditService audit;
    private final Clock clock;
    private final String managedNamespace;
    private final SecureRandom random = new SecureRandom();

    public ProjectService(JdbcTemplate jdbc, Authorization authorization, AuditService audit,
                          Clock clock, MypaasProperties properties) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
        this.clock = clock;
        this.managedNamespace = properties.getManagedNamespace();
    }

    @Transactional
    public Project create(String name, String slug, String description, long serverId,
                          ProjectConfiguration configuration) {
        authorization.require(Action.PROJECT_CREATE, ResourceRef.global());
        authorization.require(Action.SERVER_READ, ResourceRef.server(serverId));
        ProjectValidator.requireValid(slug, configuration, managedNamespace);
        if (jdbc.queryForObject("select count(*) from servers where id = ?", Integer.class, serverId) == 0) {
            throw new ValidationException("Deployment target server does not exist");
        }
        long now = clock.millis();
        String trackingKey = trackingKey();
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                var ps = connection.prepareStatement("""
                        insert into projects (name, slug, description, server_id, tracking_key, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, name);
                ps.setString(2, slug);
                ps.setString(3, description);
                ps.setLong(4, serverId);
                ps.setString(5, trackingKey);
                ps.setLong(6, now);
                ps.setLong(7, now);
                return ps;
            }, keys);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("slug_taken", "That project slug is already in use");
        }
        long projectId = keys.getKey().longValue();
        jdbc.update("insert into project_members (project_id, user_id) values (?, ?)",
                projectId, ActorContext.requireActor().id());
        jdbc.update("""
                insert into server_members (server_id, user_id)
                select ?, ? where not exists (select 1 from server_members where server_id = ? and user_id = ?)
                """, serverId, ActorContext.requireActor().id(), serverId, ActorContext.requireActor().id());
        saveConfigurationRevision(projectId, configuration, "Initial configuration");
        audit.record("PROJECT_CREATE", ResourceRef.project(projectId), AuditOutcome.SUCCESS,
                null, "created project " + slug);
        return find(projectId).orElseThrow();
    }

    public Project get(long projectId) {
        authorization.require(Action.PROJECT_READ, ResourceRef.project(projectId));
        return find(projectId).orElseThrow(() -> new NotFoundException("Project"));
    }

    public Optional<Project> find(long projectId) {
        return jdbc.query("select * from projects where id = ?", PROJECT_MAPPER, projectId)
                .stream().findFirst();
    }

    public Optional<Project> findBySlug(String slug) {
        return jdbc.query("select * from projects where slug = ?", PROJECT_MAPPER, slug)
                .stream().findFirst();
    }

    public List<Project> findAllInternal() {
        return jdbc.query("select * from projects order by name", PROJECT_MAPPER);
    }

    public PageResult<Project> list(PageRequest page) {
        AccessScope scope = authorization.visibleScope(ResourceType.PROJECT);
        List<Object> params = new ArrayList<>();
        String predicate = scope.predicate("id", params);
        Long total = jdbc.queryForObject("select count(*) from projects where " + predicate,
                Long.class, params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(page.size());
        pageParams.add(page.offset());
        List<Project> projects = jdbc.query("""
                        select * from projects where %s order by updated_at desc limit ? offset ?
                        """.formatted(predicate),
                PROJECT_MAPPER, pageParams.toArray());
        return PageResult.of(projects, page, total == null ? 0 : total);
    }

    @Transactional
    public Project update(long projectId, String name, String description, Long serverId) {
        authorization.require(Action.PROJECT_UPDATE, ResourceRef.project(projectId));
        Project project = find(projectId).orElseThrow(() -> new NotFoundException("Project"));
        Long targetServer = serverId == null ? project.serverId() : serverId;
        authorization.require(Action.SERVER_READ, ResourceRef.server(targetServer));
        long now = clock.millis();
        jdbc.update("update projects set name = ?, description = ?, server_id = ?, updated_at = ? where id = ?",
                name == null ? project.name() : name,
                description == null ? project.description() : description,
                serverId == null ? project.serverId() : serverId,
                now, projectId);
        audit.record("PROJECT_UPDATE", ResourceRef.project(projectId), AuditOutcome.SUCCESS, null, "metadata updated");
        return find(projectId).orElseThrow();
    }

    /** Creates a new revision. Existing revisions are never modified. */
    @Transactional
    public ProjectRevision saveConfiguration(long projectId, ProjectConfiguration configuration, String summary) {
        authorization.require(Action.PROJECT_UPDATE, ResourceRef.project(projectId));
        Project project = find(projectId).orElseThrow(() -> new NotFoundException("Project"));
        ProjectValidator.requireValid(project.slug(), configuration, managedNamespace);
        return saveConfigurationRevision(projectId, configuration, summary);
    }

    @Transactional
    public ProjectRevision saveConfigurationRevision(long projectId, ProjectConfiguration configuration,
                                                     String summary) {
        Project project = find(projectId).orElseThrow(() -> new NotFoundException("Project"));
        ProjectValidator.requireValid(project.slug(), configuration, managedNamespace);
        Integer last = jdbc.queryForObject(
                "select coalesce(max(revision_number), 0) from project_revisions where project_id = ?",
                Integer.class, projectId);
        int next = (last == null ? 0 : last) + 1;
        String json = Json.write(configuration);
        long now = clock.millis();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    insert into project_revisions (project_id, revision_number, configuration_json, summary,
                                                   created_at, created_by)
                    values (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, projectId);
            ps.setInt(2, next);
            ps.setString(3, json);
            ps.setString(4, summary);
            ps.setLong(5, now);
            ps.setObject(6, ActorContext.actor().map(a -> a.id()).orElse(null));
            return ps;
        }, keys);
        long revisionId = keys.getKey().longValue();
        jdbc.update("update projects set latest_revision_id = ?, updated_at = ? where id = ?",
                revisionId, now, projectId);
        audit.record("PROJECT_CONFIGURATION_SAVE", ResourceRef.project(projectId), AuditOutcome.SUCCESS,
                null, "revision " + next + (summary == null ? "" : ": " + summary));
        return revision(revisionId).orElseThrow();
    }

    @Transactional
    public ProjectRevision updateToggles(long projectId, ProjectConfiguration.Toggles toggles) {
        authorization.require(Action.PROJECT_UPDATE, ResourceRef.project(projectId));
        ProjectRevision latest = latestRevisionInternal(projectId)
                .orElseThrow(() -> new NotFoundException("Project configuration"));
        ProjectRevision revision = saveConfigurationRevision(projectId,
                latest.configuration().withToggles(toggles), "Feature toggles updated");
        audit.record("PROJECT_TOGGLES_UPDATE", ResourceRef.project(projectId), AuditOutcome.SUCCESS,
                null, "enableApplication=" + toggles.enableApplication()
                        + ", enableHttps=" + toggles.enableHttps()
                        + ", autoDeploy=" + toggles.autoDeploy()
                        + ", accessLogs=" + toggles.accessLogs()
                        + ", analytics=" + toggles.analytics());
        return revision;
    }

    public ProjectRevision configuration(long projectId) {
        authorization.require(Action.PROJECT_READ, ResourceRef.project(projectId));
        return latestRevisionInternal(projectId).orElseThrow(() -> new NotFoundException("Project configuration"));
    }

    public Optional<ProjectRevision> latestRevisionInternal(long projectId) {
        List<Long> ids = jdbc.queryForList(
                "select latest_revision_id from projects where id = ?", Long.class, projectId);
        if (ids.isEmpty() || ids.getFirst() == null) {
            return Optional.empty();
        }
        return revision(ids.getFirst());
    }

    public Optional<ProjectRevision> latestCommittedRevision(long projectId) {
        List<Long> ids = jdbc.queryForList("""
                select id from project_revisions where project_id = ?
                order by revision_number desc limit 1
                """, Long.class, projectId);
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        return revision(ids.getFirst());
    }

    public Optional<ProjectRevision> revision(long revisionId) {
        List<ProjectRevision> revisions = jdbc.query("""
                        select * from project_revisions where id = ?
                        """,
                (rs, rowNum) -> new ProjectRevision(
                        rs.getLong("id"),
                        rs.getLong("project_id"),
                        rs.getInt("revision_number"),
                        Json.read(rs.getString("configuration_json"), ProjectConfiguration.class),
                        rs.getString("summary"),
                        rs.getLong("created_at"),
                        rs.getObject("created_by", Long.class)),
                revisionId);
        return revisions.stream().findFirst();
    }

    public ProjectRevision requireRevision(long revisionId) {
        return revision(revisionId).orElseThrow(() -> new NotFoundException("Project configuration revision"));
    }

    public List<Project> projectsForUser(long userId) {
        return jdbc.query("""
                        select p.* from projects p join project_members m on m.project_id = p.id
                        where m.user_id = ? order by p.name
                        """, PROJECT_MAPPER, userId);
    }

    public List<String> memberEmails(long projectId) {
        return jdbc.queryForList("""
                select u.email from users u join project_members m on m.user_id = u.id
                where m.project_id = ? order by u.email
                """, String.class, projectId);
    }

    public List<Long> memberIds(long projectId) {
        return jdbc.queryForList("select user_id from project_members where project_id = ?",
                Long.class, projectId);
    }

    @Transactional
    public void addMember(long projectId, long userId) {
        authorization.require(Action.PROJECT_UPDATE, ResourceRef.project(projectId));
        jdbc.update("""
                insert into project_members (project_id, user_id)
                select ?, ? where not exists (select 1 from project_members where project_id = ? and user_id = ?)
                """, projectId, userId, projectId, userId);
        audit.record("PROJECT_MEMBER_ADD", ResourceRef.project(projectId), AuditOutcome.SUCCESS,
                null, "user " + userId);
    }

    private String trackingKey() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
