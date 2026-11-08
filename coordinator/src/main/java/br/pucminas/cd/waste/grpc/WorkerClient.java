package br.pucminas.cd.waste.grpc;

import br.pucminas.cd.waste.proto.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.concurrent.TimeUnit;

/** Cliente gRPC usado tanto no SLIC quanto na replica transacional. */
public class WorkerClient implements AutoCloseable {
    private final String address;
    private final ManagedChannel channel;
    private final ClassifierServiceGrpc.ClassifierServiceBlockingStub stub;
    private final long deadlineMs;

    public WorkerClient(String address, long deadlineMs) {
        this.address = address;
        this.deadlineMs = deadlineMs;
        String[] parts = address.trim().split(":");
        if (parts.length != 2) throw new IllegalArgumentException("Endereco invalido: " + address);
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);
        this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext()
                .maxInboundMessageSize(16 * 1024 * 1024).build();
        this.stub = ClassifierServiceGrpc.newBlockingStub(channel);
    }

    public String address() { return address; }
    private ClassifierServiceGrpc.ClassifierServiceBlockingStub withDeadline() {
        return stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS);
    }
    public ClassifyResponse classify(ClassifyRequest request) { return withDeadline().classify(request); }
    public SegmentResponse segment(SegmentRequest request) { return withDeadline().segment(request); }
    public HealthResponse health(long logicalTime) {
        return withDeadline().health(HealthRequest.newBuilder().setLogicalTime(logicalTime).build());
    }
    public ReplicaOperationResponse prepareReplica(PrepareReplicaRequest request) {
        return withDeadline().prepareReplica(request);
    }
    public ReplicaOperationResponse commitReplica(TransactionDecisionRequest request) {
        return withDeadline().commitReplica(request);
    }
    public ReplicaOperationResponse abortReplica(TransactionDecisionRequest request) {
        return withDeadline().abortReplica(request);
    }
    public ReplicaStatusResponse replicaStatus(ReplicaStatusRequest request) {
        return withDeadline().replicaStatus(request);
    }
    public ReadReplicaResponse readReplica(ReadReplicaRequest request) {
        return withDeadline().readReplica(request);
    }
    @Override public void close() {
        channel.shutdown();
        try {
            if (!channel.awaitTermination(1, TimeUnit.SECONDS)) channel.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            channel.shutdownNow();
        }
    }
}
