package br.pucminas.cd.waste.service;

import br.pucminas.cd.waste.clock.LamportClock;
import br.pucminas.cd.waste.dto.BatchClassificationResponse;
import br.pucminas.cd.waste.dto.ClassificationResult;
import br.pucminas.cd.waste.dto.WorkerHealth;
import br.pucminas.cd.waste.grpc.WorkerClient;
import br.pucminas.cd.waste.grpc.WorkerPool;
import br.pucminas.cd.waste.proto.ClassifyRequest;
import br.pucminas.cd.waste.proto.ClassifyResponse;
import br.pucminas.cd.waste.proto.HealthResponse;
import com.google.protobuf.ByteString;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class DistributedClassificationService {
    private final WorkerPool workerPool;
    private final LamportClock clock;
    private final ExecutorService executor;

    public DistributedClassificationService(WorkerPool workerPool, LamportClock clock) {
        this.workerPool = workerPool;
        this.clock = clock;
        this.executor = Executors.newFixedThreadPool(Math.max(2, workerPool.size() * 2));
    }

    public BatchClassificationResponse classify(List<MultipartFile> files) {
        String requestId = UUID.randomUUID().toString();
        long start = System.nanoTime();

        List<CompletableFuture<ClassificationResult>> futures = files.stream()
                .map(file -> CompletableFuture.supplyAsync(
                        () -> classifyWithFailover(requestId, file),
                        executor
                ))
                .toList();

        List<ClassificationResult> results = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        return new BatchClassificationResponse(
                requestId,
                files.size(),
                elapsedMs,
                clock.get(),
                results
        );
    }

    private ClassificationResult classifyWithFailover(String requestId, MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            return error(file.getOriginalFilename(), "FILE_READ_ERROR");
        }

        List<WorkerClient> candidates = workerPool.orderedFromNext();
        List<String> failures = new ArrayList<>();

        for (WorkerClient worker : candidates) {
            try {
                long sendTime = clock.tick();

                ClassifyRequest request = ClassifyRequest.newBuilder()
                        .setRequestId(requestId)
                        .setFilename(safeFilename(file))
                        .setImage(ByteString.copyFrom(bytes))
                        .setLogicalTime(sendTime)
                        .build();

                ClassifyResponse response = worker.classify(request);
                clock.receive(response.getLogicalTime());

                return new ClassificationResult(
                        response.getFilename(),
                        response.getLabel(),
                        response.getConfidence(),
                        response.getWorkerId(),
                        response.getStatus(),
                        response.getLogicalTime(),
                        response.getProcessingTimeMs(),
                        response.getDemoMode()
                );
            } catch (StatusRuntimeException | RuntimeException e) {
                failures.add(worker.address());
            }
        }

        return error(safeFilename(file), "WORKERS_UNAVAILABLE:" + String.join(",", failures));
    }

    public List<WorkerHealth> workersHealth() {
        List<WorkerHealth> result = new ArrayList<>();

        for (WorkerClient worker : workerPool.all()) {
            try {
                long sendTime = clock.tick();
                HealthResponse response = worker.health(sendTime);
                clock.receive(response.getLogicalTime());

                result.add(new WorkerHealth(
                        worker.address(),
                        response.getWorkerId(),
                        response.getStatus(),
                        response.getLogicalTime(),
                        response.getModelLoaded(),
                        response.getDemoMode()
                ));
            } catch (RuntimeException e) {
                result.add(new WorkerHealth(
                        worker.address(),
                        "",
                        "UNAVAILABLE",
                        clock.get(),
                        false,
                        false
                ));
            }
        }

        return result;
    }

    private ClassificationResult error(String filename, String status) {
        return new ClassificationResult(
                filename == null ? "" : filename,
                "",
                0.0,
                "",
                status,
                clock.get(),
                0,
                false
        );
    }

    private String safeFilename(MultipartFile file) {
        String name = file.getOriginalFilename();
        return name == null || name.isBlank() ? "image" : name;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
