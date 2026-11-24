package com.dettavo.feature.job.event;

import com.dettavo.feature.job.dto.request.Job;
import com.dettavo.feature.job.dto.response.JobResponse;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Slf4j
@Component
public class JobEventBroadcaster {

    public static final String EVENT_NAME = "job";
    private static final long THROTTLE_MILLISECONDS = 250;
    private static final long HEARTBEAT_SECONDS = 15;

    private final Map<UUID, Set<SseEmitter>> jobSubscribers = new ConcurrentHashMap<>();
    private final Map<UUID, Set<SseEmitter>> projectSubscribers = new ConcurrentHashMap<>();
    private final Set<Job> pending = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("job-events").daemon(true).factory());

    public JobEventBroadcaster() {
        scheduler.scheduleAtFixedRate(this::flushPending, THROTTLE_MILLISECONDS, THROTTLE_MILLISECONDS, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::sendHeartbeats, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    public SseEmitter subscribeToJob(JobResponse current) {
        SseEmitter emitter = register(jobSubscribers, current.id());
        send(emitter, current);
        return emitter;
    }

    public SseEmitter subscribeToProject(UUID projectId, List<JobResponse> current) {
        SseEmitter emitter = register(projectSubscribers, projectId);
        current.forEach(job -> send(emitter, job));
        return emitter;
    }

    /** Progress: sent on the next tick. */
    public void changed(Job job) {
        pending.add(job);
    }

    /** A status or queue change: sent at once. */
    public void changedNow(Job job) {
        pending.remove(job);
        deliver(job.snapshot());
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        allEmitters().forEach(emitter -> {
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // The connection is already gone.
            }
        });
    }

    private void flushPending() {
        try {
            for (Job job : List.copyOf(pending)) {
                pending.remove(job);
                deliver(job.snapshot());
            }
        } catch (RuntimeException e) {
            log.warn("Could not send job events: {}", e.getMessage());
        }
    }

    private void sendHeartbeats() {
        allEmitters().forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException | IllegalStateException e) {
                drop(emitter);
            }
        });
    }

    private void deliver(JobResponse job) {
        sendAll(jobSubscribers.get(job.id()), job);
        sendAll(projectSubscribers.get(job.projectId()), job);
    }

    private void sendAll(Set<SseEmitter> emitters, JobResponse job) {
        if (emitters != null) {
            List.copyOf(emitters).forEach(emitter -> send(emitter, job));
        }
    }

    private void send(SseEmitter emitter, JobResponse job) {
        try {
            emitter.send(SseEmitter.event().name(EVENT_NAME).data(job, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            drop(emitter);
        }
    }

    /** No timeout: the stream stays open until the browser closes it. */
    private SseEmitter register(Map<UUID, Set<SseEmitter>> subscribers, UUID key) {
        SseEmitter emitter = new SseEmitter(0L);
        Set<SseEmitter> emitters = subscribers.computeIfAbsent(key, id -> ConcurrentHashMap.newKeySet());
        emitters.add(emitter);
        Runnable remove = () -> emitters.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        return emitter;
    }

    private void drop(SseEmitter emitter) {
        jobSubscribers.values().forEach(emitters -> emitters.remove(emitter));
        projectSubscribers.values().forEach(emitters -> emitters.remove(emitter));
    }

    private Stream<SseEmitter> allEmitters() {
        return Stream.concat(jobSubscribers.values().stream(), projectSubscribers.values().stream()).flatMap(Set::stream);
    }
}
