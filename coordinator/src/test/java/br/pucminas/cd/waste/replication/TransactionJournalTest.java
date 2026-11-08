package br.pucminas.cd.waste.replication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class TransactionJournalTest {
    @TempDir Path directory;

    @Test void decisaoEConfirmacoesPersistemAposReabertura() {
        TransactionJournal.Entry first = new TransactionJournal.Entry(
                "tx-teste-1", "resultado-1", "a".repeat(64),
                List.of("worker-a:50051", "worker-b:50051"),
                TransactionJournal.Decision.STARTED, Set.of(), System.currentTimeMillis());
        try (TransactionJournal journal = new TransactionJournal(directory.toString())) {
            journal.save(first);
            journal.save(first.withDecision(TransactionJournal.Decision.COMMIT));
            journal.save(journal.read("tx-teste-1").withAck("worker-a:50051"));
            assertEquals("COMMIT_PENDING", journal.read("tx-teste-1").publicStatus());
        }
        try (TransactionJournal reopened = new TransactionJournal(directory.toString())) {
            assertEquals(TransactionJournal.Decision.COMMIT, reopened.read("tx-teste-1").decision());
            assertEquals(Set.of("worker-a:50051"), reopened.read("tx-teste-1").acknowledged());
            reopened.save(reopened.read("tx-teste-1").withAck("worker-b:50051"));
            assertEquals("COMMITTED", reopened.read("tx-teste-1").publicStatus());
            assertEquals(1, reopened.all().size());
        }
    }

    @Test void naoPermiteReverterDecisao() {
        var e = new TransactionJournal.Entry("tx-teste-2", "res-2", "b".repeat(64),
                List.of("a", "b"), TransactionJournal.Decision.STARTED, Set.of(), 1);
        try (TransactionJournal j = new TransactionJournal(directory.toString())) {
            j.save(e);
            j.save(e.withDecision(TransactionJournal.Decision.COMMIT));
            assertThrows(IllegalStateException.class,
                    () -> j.save(e.withDecision(TransactionJournal.Decision.ABORT)));
            assertThrows(IllegalStateException.class,
                    () -> j.read("tx-teste-2").withDecision(TransactionJournal.Decision.ABORT));
        }
    }

    @Test void bloqueiaSegundoCoordenadorNaMesmaPasta() {
        try (TransactionJournal first = new TransactionJournal(directory.toString())) {
            assertThrows(RuntimeException.class,
                    () -> new TransactionJournal(directory.toString()));
        }
    }
}
