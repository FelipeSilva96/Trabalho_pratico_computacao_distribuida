package br.pucminas.cd.waste.replication;

import br.pucminas.cd.waste.clock.LamportClock;
import br.pucminas.cd.waste.grpc.WorkerClient;
import br.pucminas.cd.waste.grpc.WorkerPool;
import br.pucminas.cd.waste.proto.ReplicaOperationResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReplicationCoordinatorTest {
    @TempDir Path root;
    private byte[] tinyPng() {
        try {
            var out = new ByteArrayOutputStream();
            var img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ReplicaOperationResponse response(String tx, String status) {
        return ReplicaOperationResponse.newBuilder().setTransactionId(tx)
                .setState(status).setLogicalTime(10).setWorkerId("teste").build();
    }

    @Test void commitSomenteDepoisDosDoisVotos() {
        WorkerPool pool=mock(WorkerPool.class);
        WorkerClient a=mock(WorkerClient.class), b=mock(WorkerClient.class);
        when(a.address()).thenReturn("a:50051"); when(b.address()).thenReturn("b:50051");
        when(pool.all()).thenReturn(List.of(a,b));
        when(a.prepareReplica(any())).thenAnswer(i -> response(i.getArgument(0, br.pucminas.cd.waste.proto.PrepareReplicaRequest.class).getTransactionId(), "PREPARED"));
        when(b.prepareReplica(any())).thenAnswer(i -> response(i.getArgument(0, br.pucminas.cd.waste.proto.PrepareReplicaRequest.class).getTransactionId(), "PREPARED"));
        when(a.commitReplica(any())).thenAnswer(i -> response(i.getArgument(0, br.pucminas.cd.waste.proto.TransactionDecisionRequest.class).getTransactionId(), "COMMITTED"));
        when(b.commitReplica(any())).thenAnswer(i -> response(i.getArgument(0, br.pucminas.cd.waste.proto.TransactionDecisionRequest.class).getTransactionId(), "COMMITTED"));
        try(var journal=new TransactionJournal(root.toString())) {
            var coordinator=new ReplicationCoordinator(pool,new LamportClock(),journal);
            var result=coordinator.replicate(tinyPng());
            assertEquals("COMMITTED",result.status());
            assertEquals(2,result.acknowledgedCount());
            assertEquals(TransactionJournal.Decision.COMMIT,journal.read(result.transactionId()).decision());
            verify(a).prepareReplica(any()); verify(b).prepareReplica(any());
            verify(a).commitReplica(any()); verify(b).commitReplica(any());
            verify(a,never()).abortReplica(any());
        }
    }

    @Test void timeoutDeCommitNaoReverteDecisaoENovoRetryConclui() {
        WorkerPool pool=mock(WorkerPool.class);
        WorkerClient a=mock(WorkerClient.class), b=mock(WorkerClient.class);
        when(a.address()).thenReturn("a:50051"); when(b.address()).thenReturn("b:50051");
        when(pool.all()).thenReturn(List.of(a,b));
        when(a.prepareReplica(any())).thenAnswer(i -> response(i.getArgument(0, br.pucminas.cd.waste.proto.PrepareReplicaRequest.class).getTransactionId(), "PREPARED"));
        when(b.prepareReplica(any())).thenAnswer(i -> response(i.getArgument(0, br.pucminas.cd.waste.proto.PrepareReplicaRequest.class).getTransactionId(), "PREPARED"));
        when(a.commitReplica(any())).thenAnswer(i -> response(i.getArgument(0, br.pucminas.cd.waste.proto.TransactionDecisionRequest.class).getTransactionId(), "COMMITTED"));
        when(b.commitReplica(any())).thenThrow(new StatusRuntimeException(Status.UNAVAILABLE))
                .thenAnswer(i -> response(i.getArgument(0, br.pucminas.cd.waste.proto.TransactionDecisionRequest.class).getTransactionId(), "COMMITTED"));
        try(var journal=new TransactionJournal(root.toString())) {
            var coordinator=new ReplicationCoordinator(pool,new LamportClock(),journal);
            var status=coordinator.replicate(tinyPng());
            assertEquals("COMMIT_PENDING",status.status());
            assertEquals(TransactionJournal.Decision.COMMIT,journal.read(status.transactionId()).decision());
            coordinator.recover();
            assertEquals("COMMITTED",coordinator.status(status.transactionId()).status());
            verify(b,times(2)).commitReplica(any());
            verify(b,never()).abortReplica(any());
        }
    }

    @Test void votoFalhoProvocaAbortEmAmbasAsReplicas() {
        WorkerPool pool=mock(WorkerPool.class);
        WorkerClient a=mock(WorkerClient.class), b=mock(WorkerClient.class);
        when(a.address()).thenReturn("a:50051"); when(b.address()).thenReturn("b:50051");
        when(pool.all()).thenReturn(List.of(a,b));
        when(a.prepareReplica(any())).thenAnswer(i -> response(i.getArgument(0, br.pucminas.cd.waste.proto.PrepareReplicaRequest.class).getTransactionId(), "PREPARED"));
        when(b.prepareReplica(any())).thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
        when(a.abortReplica(any())).thenAnswer(i -> response(i.getArgument(0, br.pucminas.cd.waste.proto.TransactionDecisionRequest.class).getTransactionId(), "ABORTED"));
        when(b.abortReplica(any())).thenAnswer(i -> response(i.getArgument(0, br.pucminas.cd.waste.proto.TransactionDecisionRequest.class).getTransactionId(), "ABORTED"));
        try(var journal=new TransactionJournal(root.toString())) {
            var coordinator=new ReplicationCoordinator(pool,new LamportClock(),journal);
            var result=coordinator.replicate(tinyPng());
            assertEquals("ABORTED",result.status());
            verify(a).abortReplica(any()); verify(b).abortReplica(any());
            verify(a,never()).commitReplica(any());
        }
    }
}
