package br.pucminas.cd.waste.replication;

import br.pucminas.cd.waste.clock.LamportClock;
import br.pucminas.cd.waste.grpc.WorkerClient;
import br.pucminas.cd.waste.grpc.WorkerPool;
import br.pucminas.cd.waste.proto.PrepareReplicaRequest;
import br.pucminas.cd.waste.proto.ReplicaOperationResponse;
import br.pucminas.cd.waste.proto.ReadReplicaRequest;
import br.pucminas.cd.waste.proto.ReadReplicaResponse;
import br.pucminas.cd.waste.proto.TransactionDecisionRequest;
import com.google.protobuf.ByteString;
import io.grpc.StatusRuntimeException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Coordinador 2PC com decisao duravel, confirmacao em TODAS as replicas e replay.
 * E um laboratorio de um unico coordenador (single leader), sem consenso ou TLS.
 */
@Service
public class ReplicationCoordinator {
    // Margem para o overhead do protobuf e limites de 16 MiB dos servidores gRPC.
    private static final int MAX_PNG_BYTES = 14 * 1024 * 1024;
    private static final byte[] PNG_MAGIC = new byte[] {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    public record TransactionView(String transactionId, String resultId, String sha256,
                                  String status, String decision, int replicaCount,
                                  int acknowledgedCount, List<String> participants,
                                  Set<String> acknowledged) { }

    private final WorkerPool workerPool;
    private final LamportClock clock;
    private final TransactionJournal journal;

    public ReplicationCoordinator(WorkerPool workerPool, LamportClock clock,
                                  TransactionJournal journal) {
        this.workerPool = workerPool;
        this.clock = clock;
        this.journal = journal;
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void validatePng(byte[] png) {
        if (png == null || png.length < PNG_MAGIC.length || png.length > MAX_PNG_BYTES)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PNG ausente ou maior que 14 MiB");
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            if (png[i] != PNG_MAGIC[i])
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Arquivo precisa ser PNG");
        }
    }

    private List<String> participantAddresses() {
        List<String> addresses = workerPool.all().stream().map(WorkerClient::address).toList();
        if (addresses.size() < 2 || new HashSet<>(addresses).size() != addresses.size())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Replicacao transacional exige ao menos dois workers distintos");
        return addresses;
    }

    /** Nova transacao. Nao usa retry em outro worker no PREPARE: o conjunto e fixo. */
    public synchronized TransactionView replicate(byte[] png) {
        validatePng(png);
        List<String> participants = participantAddresses();
        String txId = "tx-" + UUID.randomUUID();
        String resultId = "result-" + UUID.randomUUID();
        String sha = sha256(png);
        TransactionJournal.Entry entry = new TransactionJournal.Entry(
                txId, resultId, sha, participants, TransactionJournal.Decision.STARTED,
                Set.of(), System.currentTimeMillis());
        journal.save(entry); // MUST ser persistido ANTES de qualquer PREPARE.

        boolean allPrepared = true;
        for (String address : participants) {
            WorkerClient client = findClient(address);
            PrepareReplicaRequest request = PrepareReplicaRequest.newBuilder()
                    .setTransactionId(txId).setResultId(resultId)
                    .setImagePng(ByteString.copyFrom(png)).setSha256(sha)
                    .setLogicalTime(clock.tick()).build();
            try {
                ReplicaOperationResponse vote = client.prepareReplica(request);
                clock.receive(vote.getLogicalTime());
                if (!txId.equals(vote.getTransactionId())
                        || !("PREPARED".equals(vote.getState()) || "COMMITTED".equals(vote.getState()))) {
                    allPrepared = false;
                    System.err.println("[2PC] Voto inesperado de " + address + " : " + vote.getState());
                    break;
                }
                System.out.println("[2PC] PREPARED " + txId + " -> " + address);
            } catch (StatusRuntimeException e) {
                allPrepared = false;
                System.err.println("[2PC] PREPARE falhou " + txId + " -> " + address + " : " + e.getStatus());
                break;
            }
        }
        // A decisao global e persistida ANTES de ser enviada a qualquer participante.
        // Se a gravacao falhar neste ponto, nao enviar decisao incerta: a recuperacao
        // le o journal e decide ABORT se ele ainda estiver em STARTED.
        TransactionJournal.Decision decision = allPrepared
                ? TransactionJournal.Decision.COMMIT : TransactionJournal.Decision.ABORT;
        journal.save(entry.withDecision(decision));
        System.out.println("[2PC] DECISION " + txId + " -> " + decision);
        deliverDecision(journal.read(txId));
        return status(txId);
    }

    private WorkerClient findClient(String address) {
        return workerPool.all().stream().filter(w -> w.address().equals(address)).findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Participante do journal ausente da configuracao atual: " + address));
    }

    /** Retenta SOMENTE a decisao persistida. NUNCA muda COMMIT para ABORT. */
    private void deliverDecision(TransactionJournal.Entry entry) {
        if (entry.decision() == TransactionJournal.Decision.STARTED)
            throw new IllegalStateException("Nao pode enviar decisao antes de persisti-la");
        for (String address : entry.participants()) {
            TransactionJournal.Entry latest = journal.read(entry.transactionId());
            if (latest.acknowledged().contains(address)) continue;
            try {
                WorkerClient worker = findClient(address);
                TransactionDecisionRequest request = TransactionDecisionRequest.newBuilder()
                        .setTransactionId(entry.transactionId()).setLogicalTime(clock.tick()).build();
                ReplicaOperationResponse response = entry.decision() == TransactionJournal.Decision.COMMIT
                        ? worker.commitReplica(request) : worker.abortReplica(request);
                clock.receive(response.getLogicalTime());
                String expected = entry.decision() == TransactionJournal.Decision.COMMIT ? "COMMITTED" : "ABORTED";
                if (!entry.transactionId().equals(response.getTransactionId())
                        || !expected.equals(response.getState()))
                    throw new IllegalStateException("ACK invalido de " + address + ": " + response.getState());
                journal.save(latest.withAck(address));
                System.out.println("[2PC] ACK " + expected + " " + entry.transactionId() + " <- " + address);
            } catch (StatusRuntimeException | IllegalStateException e) {
                // Decisao continua duravel. O job de recovery ira tentar novamente.
                System.err.println("[2PC] ACK pendente " + entry.transactionId() + " de " + address + " : " + e);
            }
        }
    }

    public synchronized TransactionView status(String txId) {
        TransactionJournal.Entry e = journal.read(txId);
        if (e == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transacao desconhecida");
        return view(e);
    }

    private TransactionView view(TransactionJournal.Entry e) {
        return new TransactionView(e.transactionId(), e.resultId(), e.sha256(),
                e.publicStatus(), e.decision().name(), e.participants().size(),
                e.acknowledged().size(), e.participants(), e.acknowledged());
    }

    /** So retorna bytes depois dos ACKs de todas as replicas. */
    public synchronized byte[] readPublished(String resultId) {
        TransactionJournal.Entry record = journal.all().stream()
                .filter(e -> e.resultId().equals(resultId)).findFirst().orElse(null);
        if (record == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resultado desconhecido");
        if (record.decision() != TransactionJournal.Decision.COMMIT || !record.complete())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Resultado ainda nao publicado: " + record.publicStatus());
        List<String> errors = new ArrayList<>();
        for (String address : record.participants()) {
            try {
                ReadReplicaResponse response = findClient(address).readReplica(
                        ReadReplicaRequest.newBuilder().setResultId(resultId)
                                .setLogicalTime(clock.tick()).build());
                clock.receive(response.getLogicalTime());
                byte[] png = response.getImagePng().toByteArray();
                if (!resultId.equals(response.getResultId())
                        || !record.sha256().equals(sha256(png))
                        || !record.sha256().equals(response.getSha256()))
                    throw new IllegalStateException("SHA-256 divergente na replica");
                return png;
            } catch (StatusRuntimeException | IllegalStateException ex) {
                errors.add(address + ": " + ex.getMessage());
            }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Nenhuma replica valida disponivel: " + String.join("; ", errors));
    }

    /** Rotina automatica de recovery; tambem acionavel manualmente pelo REST. */
    @Scheduled(fixedDelayString = "${replication.recovery-interval-ms:15000}",
            initialDelayString = "${replication.recovery-initial-delay-ms:7000}")
    public synchronized void recover() {
        for (TransactionJournal.Entry record : journal.all()) {
            if (record.decision() == TransactionJournal.Decision.STARTED) {
                // Nenhum COMMIT pode ter sido enviado: registro STARTED obriga ABORT.
                journal.save(record.withDecision(TransactionJournal.Decision.ABORT));
                record = journal.read(record.transactionId());
            }
            if (!record.complete()) deliverDecision(record);
        }
    }
}
