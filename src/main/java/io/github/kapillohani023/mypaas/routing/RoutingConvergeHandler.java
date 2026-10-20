package io.github.kapillohani023.mypaas.routing;

import io.github.kapillohani023.mypaas.core.Json;
import io.github.kapillohani023.mypaas.jobs.Job;
import io.github.kapillohani023.mypaas.jobs.JobLease;
import io.github.kapillohani023.mypaas.jobs.JobTypes;
import io.github.kapillohani023.mypaas.jobs.TypedJobHandler;
import org.springframework.stereotype.Component;

@Component
public class RoutingConvergeHandler implements TypedJobHandler {

    private final RoutingService routing;

    public RoutingConvergeHandler(RoutingService routing) {
        this.routing = routing;
    }

    @Override
    public String type() {
        return JobTypes.ROUTING_CONVERGE;
    }

    @Override
    public void handle(Job job, JobLease lease) {
        RoutingServiceImpl.ConvergePayload payload =
                Json.read(job.payloadJson(), RoutingServiceImpl.ConvergePayload.class);
        lease.heartbeat();
        routing.converge(payload.projectId());
    }
}
