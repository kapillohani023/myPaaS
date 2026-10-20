package io.github.kapillohani023.mypaas.jobs;

/** Closed set of v1 job types. */
public final class JobTypes {
    public static final String DEPLOYMENT_RUN = "deployment.run";
    public static final String ROUTING_CONVERGE = "routing.converge";
    public static final String MONITORING_COLLECT = "monitoring.collect";
    public static final String ANALYTICS_INGEST = "analytics.ingest";
    public static final String NOTIFICATION_SEND = "notification.send";
    public static final String RETENTION_PURGE = "retention.purge";

    private JobTypes() {
    }
}
