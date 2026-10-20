package io.github.kapillohani023.mypaas.deployment;

import io.github.kapillohani023.mypaas.core.ConflictException;
import io.github.kapillohani023.mypaas.core.NotFoundException;
import io.github.kapillohani023.mypaas.security.Trigger;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

@Repository
public class DeploymentRepository {

    private static final RowMapper<Deployment> MAPPER = (rs, rowNum) -> new Deployment(
            rs.getLong("id"),
            rs.getLong("project_id"),
            rs.getLong("revision_id"),
            rs.getLong("server_id"),
            Trigger.valueOf(rs.getString("trigger_source")),
            rs.getString("kind"),
            rs.getObject("job_id", Long.class),
            rs.getObject("actor_id", Long.class),
            rs.getString("actor_email"),
            DeploymentState.valueOf(rs.getString("state")),
            rs.getString("ref_override"),
            rs.getString("commit_sha"),
            rs.getString("failure_summary"),
            rs.getObject("release_id", Long.class),
            rs.getObject("previous_release_id", Long.class),
            rs.getObject("rollback_of", Long.class),
            rs.getString("idempotency_key"),
            rs.getBoolean("cancel_requested"),
            rs.getLong("created_at"),
            rs.getObject("started_at", Long.class),
            rs.getObject("finished_at", Long.class));

    private static final RowMapper<Release> RELEASE_MAPPER = (rs, rowNum) -> new Release(
            rs.getLong("id"),
            rs.getLong("deployment_id"),
            rs.getLong("project_id"),
            rs.getLong("server_id"),
            rs.getLong("revision_id"),
            rs.getString("version"),
            rs.getString("path"),
            rs.getString("commit_sha"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DeploymentRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public long insert(long projectId, long revisionId, long serverId, Trigger trigger, String kind,
                       Long actorId, String actorEmail, String refOverride, Long previousReleaseId,
                       Long rollbackOf, String idempotencyKey) {
        KeyHolder keys = new GeneratedKeyHolder();
        long now = clock.millis();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    insert into deployments (project_id, revision_id, server_id, trigger_source, kind,
                                             actor_id, actor_email, state, ref_override, previous_release_id,
                                             rollback_of, idempotency_key, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, projectId);
            ps.setLong(2, revisionId);
            ps.setLong(3, serverId);
            ps.setString(4, trigger.name());
            ps.setString(5, kind);
            ps.setObject(6, actorId);
            ps.setString(7, actorEmail);
            ps.setString(8, refOverride);
            ps.setObject(9, previousReleaseId);
            ps.setObject(10, rollbackOf);
            ps.setString(11, idempotencyKey);
            ps.setLong(12, now);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Deployment require(long id) {
        return find(id).orElseThrow(() -> new NotFoundException("Deployment"));
    }

    public Optional<Deployment> find(long id) {
        return jdbc.query("select * from deployments where id = ?", MAPPER, id).stream().findFirst();
    }

    public List<Deployment> history(long projectId, int limit, int offset) {
        return jdbc.query("""
                select * from deployments where project_id = ?
                order by id desc limit ? offset ?
                """, MAPPER, projectId, limit, offset);
    }

    public long historyCount(long projectId) {
        Long count = jdbc.queryForObject("select count(*) from deployments where project_id = ?",
                Long.class, projectId);
        return count == null ? 0 : count;
    }

    public Optional<Deployment> latest(long projectId) {
        return jdbc.query("select * from deployments where project_id = ? order by id desc limit 1",
                MAPPER, projectId).stream().findFirst();
    }

    public Optional<Deployment> latestSuccessful(long projectId) {
        return jdbc.query("""
                select * from deployments where project_id = ? and state = 'SUCCEEDED'
                order by id desc limit 1
                """, MAPPER, projectId).stream().findFirst();
    }

    public long countActive(long projectId) {
        Long count = jdbc.queryForObject("""
                select count(*) from deployments where project_id = ?
                and state not in ('SUCCEEDED','FAILED','CANCELLED')
                """, Long.class, projectId);
        return count == null ? 0 : count;
    }

    public void setJob(long deploymentId, long jobId) {
        jdbc.update("update deployments set job_id = ? where id = ?", jobId, deploymentId);
    }

    @Transactional
    public void transition(long deploymentId, DeploymentState expected, DeploymentState next, String detail) {
        if (!expected.canTransitionTo(next)) {
            throw new ConflictException("invalid_transition",
                    "Cannot transition deployment from " + expected + " to " + next);
        }
        long now = clock.millis();
        int updated = jdbc.update("""
                update deployments
                set state = ?,
                    started_at = case when started_at is null then ? else started_at end,
                    finished_at = case when ? then ? else null end
                where id = ? and state = ?
                """, next.name(), now, next.terminal(), now, deploymentId, expected.name());
        if (updated == 0) {
            throw new ConflictException("deployment_state_conflict",
                    "Deployment state changed concurrently");
        }
        closeOpenStep(deploymentId, now);
        Integer last = jdbc.queryForObject("""
                select coalesce(max(seq), 0) from deployment_steps where deployment_id = ?
                """, Integer.class, deploymentId);
        jdbc.update("""
                insert into deployment_steps (deployment_id, seq, name, state, detail, started_at, finished_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, deploymentId, (last == null ? 0 : last) + 1, next.name(),
                next.terminal() ? next.name() : "RUNNING", detail, now, next.terminal() ? now : null);
    }

    public void updateTransitionDetail(long deploymentId, String state, String detail) {
        jdbc.update("""
                update deployment_steps set detail = ?
                where deployment_id = ? and name = ? and seq = (
                    select max(seq) from deployment_steps where deployment_id = ? and name = ?
                )
                """, detail, deploymentId, state, deploymentId, state);
    }

    public void finishStep(long deploymentId, String state, String detail) {
        jdbc.update("""
                update deployment_steps set state = 'DONE', detail = ?, finished_at = ?
                where deployment_id = ? and name = ? and finished_at is null
                """, detail, clock.millis(), deploymentId, state);
    }

    private void closeOpenStep(long deploymentId, long now) {
        jdbc.update("""
                update deployment_steps set finished_at = ?, state = 'DONE'
                where deployment_id = ? and finished_at is null
                """, now, deploymentId);
    }

    public void setCommit(long deploymentId, String commitSha) {
        jdbc.update("update deployments set commit_sha = ? where id = ?", commitSha, deploymentId);
    }

    public void setRelease(long deploymentId, long releaseId) {
        jdbc.update("update deployments set release_id = ? where id = ?", releaseId, deploymentId);
    }

    public void fail(long deploymentId, String summary) {
        long now = clock.millis();
        jdbc.update("""
                update deployments set state = 'FAILED', failure_summary = ?, finished_at = ?
                where id = ? and state not in ('SUCCEEDED','FAILED','CANCELLED')
                """, truncate(summary, 1000), now, deploymentId);
        jdbc.update("""
                update deployment_steps set state = 'FAILED', finished_at = ?
                where deployment_id = ? and finished_at is null
                """, now, deploymentId);
    }

    public void cancelFinal(long deploymentId, String summary) {
        long now = clock.millis();
        jdbc.update("""
                update deployments set state = 'CANCELLED', failure_summary = ?, finished_at = ?
                where id = ? and state not in ('SUCCEEDED','FAILED','CANCELLED')
                """, truncate(summary, 1000), now, deploymentId);
        jdbc.update("""
                update deployment_steps set state = 'CANCELLED', finished_at = ?
                where deployment_id = ? and finished_at is null
                """, now, deploymentId);
    }

    public void requestCancel(long deploymentId) {
        jdbc.update("update deployments set cancel_requested = true where id = ?", deploymentId);
    }

    /** Restart recovery: a non-terminal deployment re-enters the pipeline from QUEUED. */
    @Transactional
    public void resetForRecovery(long deploymentId) {
        jdbc.update("""
                update deployments set state = 'QUEUED', started_at = null, finished_at = null,
                                      release_id = null
                where id = ? and state not in ('SUCCEEDED','FAILED','CANCELLED')
                """, deploymentId);
        jdbc.update("delete from deployment_steps where deployment_id = ?", deploymentId);
    }

    @Transactional
    public long insertRelease(long deploymentId, long projectId, long serverId, long revisionId,
                              String version, String path, String commitSha) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    insert into releases (deployment_id, project_id, server_id, revision_id, version, path,
                                          commit_sha, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, deploymentId);
            ps.setLong(2, projectId);
            ps.setLong(3, serverId);
            ps.setLong(4, revisionId);
            ps.setString(5, version);
            ps.setString(6, path);
            ps.setString(7, commitSha);
            ps.setLong(8, clock.millis());
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<Release> release(Long releaseId) {
        if (releaseId == null) {
            return Optional.empty();
        }
        return jdbc.query("select * from releases where id = ?", RELEASE_MAPPER, releaseId)
                .stream().findFirst();
    }

    public Release requireRelease(long releaseId) {
        return release(releaseId).orElseThrow(() -> new NotFoundException("Release"));
    }

    public List<Release> releases(long projectId, int limit) {
        return jdbc.query("""
                select * from releases where project_id = ? order by id desc limit ?
                """, RELEASE_MAPPER, projectId, limit);
    }

    public Long activeReleaseId(long projectId) {
        return jdbc.queryForObject("select active_release_id from projects where id = ?",
                Long.class, projectId);
    }

    public void setActiveRelease(long projectId, Long releaseId) {
        jdbc.update("update projects set active_release_id = ? where id = ?", releaseId, projectId);
    }

    public Optional<Long> findIdempotent(long actorId, long projectId, String key) {
        return jdbc.queryForList("""
                select deployment_id from idempotency_keys
                where actor_id = ? and project_id = ? and request_key = ?
                """, Long.class, actorId, projectId, key).stream().findFirst();
    }

    public void saveIdempotency(long actorId, long projectId, String key, long deploymentId) {
        try {
            jdbc.update("""
                    insert into idempotency_keys (actor_id, project_id, request_key, deployment_id, created_at)
                    values (?, ?, ?, ?, ?)
                    """, actorId, projectId, key, deploymentId, clock.millis());
        } catch (DuplicateKeyException ignored) {
            // A concurrent replay won the insert; the lookup path will return that deployment.
        }
    }

    public long countByState(DeploymentState state) {
        Long count = jdbc.queryForObject("select count(*) from deployments where state = ?",
                Long.class, state.name());
        return count == null ? 0 : count;
    }

    public List<DeploymentStep> steps(long deploymentId) {
        return jdbc.query("""
                        select * from deployment_steps where deployment_id = ? order by seq
                        """,
                (rs, rowNum) -> new DeploymentStep(
                        rs.getLong("id"),
                        rs.getLong("deployment_id"),
                        rs.getInt("seq"),
                        rs.getString("name"),
                        rs.getString("state"),
                        rs.getString("detail"),
                        rs.getLong("started_at"),
                        rs.getObject("finished_at", Long.class)),
                deploymentId);
    }

    public long countAllActive() {
        Long count = jdbc.queryForObject("""
                select count(*) from deployments
                where state not in ('SUCCEEDED','FAILED','CANCELLED')
                """, Long.class);
        return count == null ? 0 : count;
    }

    public void clearActiveReleaseIf(long projectId, long releaseId) {
        jdbc.update("""
                update projects set active_release_id = null
                where id = ? and active_release_id = ?
                """, projectId, releaseId);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
