package io.github.kapillohani023.mypaas.jobs;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class JobHandlerRegistry {

    private final Map<String, JobHandler> handlers = new HashMap<>();

    public JobHandlerRegistry(List<TypedJobHandler> typedHandlers) {
        for (TypedJobHandler handler : typedHandlers) {
            JobHandler previous = handlers.put(handler.type(), handler);
            if (previous != null) {
                throw new IllegalStateException("Duplicate job handler for " + handler.type());
            }
        }
    }

    public JobHandler require(String type) {
        JobHandler handler = handlers.get(type);
        if (handler == null) {
            throw new PermanentJobFailure("No job handler registered for type " + type);
        }
        return handler;
    }
}
