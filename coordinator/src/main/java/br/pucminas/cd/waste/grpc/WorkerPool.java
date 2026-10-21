package br.pucminas.cd.waste.grpc;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class WorkerPool {
    private final List<WorkerClient> workers;
    private final AtomicInteger nextIndex = new AtomicInteger(0);

    public WorkerPool(
            @Value("${workers.addresses}") String addresses,
            @Value("${grpc.deadline-ms}") long deadlineMs
    ) {
        this.workers = Arrays.stream(addresses.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(address -> new WorkerClient(address, deadlineMs))
                .toList();

        if (workers.isEmpty()) {
            throw new IllegalStateException("Nenhum worker configurado");
        }
    }

    public List<WorkerClient> orderedFromNext() {
        int start = Math.floorMod(nextIndex.getAndIncrement(), workers.size());
        if (workers.size() == 1) {
            return workers;
        }

        List<WorkerClient> ordered = new java.util.ArrayList<>(workers.size());
        for (int i = 0; i < workers.size(); i++) {
            ordered.add(workers.get((start + i) % workers.size()));
        }
        return Collections.unmodifiableList(ordered);
    }

    public List<WorkerClient> all() {
        return workers;
    }

    public int size() {
        return workers.size();
    }

    @PreDestroy
    public void shutdown() {
        workers.forEach(WorkerClient::close);
    }
}
