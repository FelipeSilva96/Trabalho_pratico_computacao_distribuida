package br.pucminas.cd.waste.grpc;

import br.pucminas.cd.waste.proto.ClassifierServiceGrpc;
import br.pucminas.cd.waste.proto.ClassifyRequest;
import br.pucminas.cd.waste.proto.ClassifyResponse;
import br.pucminas.cd.waste.proto.HealthRequest;
import br.pucminas.cd.waste.proto.HealthResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.concurrent.TimeUnit;

public class WorkerClient implements AutoCloseable {
    private final String address;
    private final ManagedChannel channel;
    private final ClassifierServiceGrpc.ClassifierServiceBlockingStub stub;
    private final long deadlineMs;

    public WorkerClient(String address, long deadlineMs) {
        this.address = address;
        this.deadlineMs = deadlineMs;

        String[] parts = address.trim().split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Endereço de worker inválido: " + address);
        }

        String host = parts[0];
        int port = Integer.parseInt(parts[1]);

        this.channel = ManagedChannelBuilder
                .forAddress(host, port)
                .usePlaintext()
                .build();

        this.stub = ClassifierServiceGrpc.newBlockingStub(channel);
    }

    public String address() {
        return address;
    }

    public ClassifyResponse classify(ClassifyRequest request) {
        return stub
                .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                .classify(request);
    }

    public HealthResponse health(long logicalTime) {
        HealthRequest request = HealthRequest.newBuilder()
                .setLogicalTime(logicalTime)
                .build();

        return stub
                .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                .health(request);
    }

    @Override
    public void close() {
        channel.shutdown();
        try {
            channel.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            channel.shutdownNow();
        }
    }
}
