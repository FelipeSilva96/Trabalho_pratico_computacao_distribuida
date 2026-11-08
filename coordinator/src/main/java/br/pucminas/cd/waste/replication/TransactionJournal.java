package br.pucminas.cd.waste.replication;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Journal de decisao local: primeiro escreve o arquivo temporario e faz fsync,
 * depois renomeia atomicamente e faz fsync do diretorio. Pressupoe filesystem
 * Linux local com suporte a rename atomico e volume persistente exclusivo.
 * O lock evita dois coordenadores gravando o MESMO diretorio simultaneamente.
 */
@Component
public class TransactionJournal implements AutoCloseable {
    public enum Decision { STARTED, COMMIT, ABORT }

    public record Entry(String transactionId, String resultId, String sha256,
                        List<String> participants, Decision decision,
                        Set<String> acknowledged, long updatedAt) {
        public Entry {
            participants = List.copyOf(participants);
            acknowledged = Set.copyOf(acknowledged);
        }
        public Entry withDecision(Decision next) {
            if (decision == Decision.COMMIT && next != Decision.COMMIT)
                throw new IllegalStateException("COMMIT nunca pode ser revertido");
            if (decision == Decision.ABORT && next != Decision.ABORT)
                throw new IllegalStateException("ABORT nunca pode ser revertido");
            return new Entry(transactionId, resultId, sha256, participants,
                    next, acknowledged, System.currentTimeMillis());
        }
        public Entry withAck(String address) {
            if (!participants.contains(address)) throw new IllegalArgumentException("Worker desconhecido");
            Set<String> updated = new HashSet<>(acknowledged);
            updated.add(address);
            return new Entry(transactionId, resultId, sha256, participants,
                    decision, updated, System.currentTimeMillis());
        }
        public boolean complete() { return acknowledged.containsAll(participants); }
        public String publicStatus() {
            return switch (decision) {
                case STARTED -> "PREPARING";
                case COMMIT -> complete() ? "COMMITTED" : "COMMIT_PENDING";
                case ABORT -> complete() ? "ABORTED" : "ABORT_PENDING";
            };
        }
    }

    private final Path root;
    private final FileChannel lockChannel;
    private final FileLock lock;

    public TransactionJournal(@Value("${replication.journal-dir:/app/data/transactions}") String path) {
        try {
            root = Path.of(path);
            Files.createDirectories(root);
            FileChannel channel = FileChannel.open(root.resolve(".coordinator.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock acquired;
            try {
                acquired = channel.tryLock();
                if (acquired == null) throw new IllegalStateException("Journal ja esta em uso");
            } catch (RuntimeException | Error ex) {
                try { channel.close(); } catch (IOException ignored) { }
                throw ex;
            }
            lockChannel = channel;
            lock = acquired;
        } catch (IOException e) {
            throw new IllegalStateException("Nao foi possivel abrir journal duravel: " + path, e);
        }
    }

    private Path entryFile(String txId) {
        if (!txId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
            throw new IllegalArgumentException("Identificador de transacao invalido");
        return root.resolve(txId + ".properties");
    }

    public synchronized Entry read(String txId) {
        Path path = entryFile(txId);
        if (!Files.isRegularFile(path)) return null;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            p.load(in);
            String rid = p.getProperty("resultId");
            String sha = p.getProperty("sha256");
            Decision d = Decision.valueOf(p.getProperty("decision"));
            long updated = Long.parseLong(p.getProperty("updatedAt"));
            int size = Integer.parseInt(p.getProperty("participantCount"));
            if (size < 1 || size > 100) throw new IllegalStateException("Numero de replicas invalido");
            List<String> participants = new ArrayList<>();
            Set<String> acks = new HashSet<>();
            for (int i = 0; i < size; i++) {
                String a = p.getProperty("participant." + i);
                if (a == null) throw new IllegalStateException("Registro de participante ausente");
                participants.add(a);
                if (Boolean.parseBoolean(p.getProperty("ack." + i, "false"))) acks.add(a);
            }
            return new Entry(txId, rid, sha, participants, d, acks, updated);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Journal corrompido ou ilegivel: " + txId, e);
        }
    }

    public synchronized List<Entry> all() {
        List<Entry> entries = new ArrayList<>();
        try (var files = Files.list(root)) {
            for (Path f : files.filter(x -> x.getFileName().toString().endsWith(".properties")).toList()) {
                String n = f.getFileName().toString();
                entries.add(read(n.substring(0, n.length() - ".properties".length())));
            }
            return List.copyOf(entries);
        } catch (IOException e) {
            throw new IllegalStateException("Nao foi possivel examinar journal", e);
        }
    }

    public synchronized void save(Entry e) {
        Path target = entryFile(e.transactionId());
        Entry old = read(e.transactionId());
        if (old != null) {
            if (!old.resultId().equals(e.resultId()) || !old.sha256().equals(e.sha256())
                    || !old.participants().equals(e.participants()))
                throw new IllegalStateException("Alteracao de identidade ou conjunto de replicas nao permitida");
            if (old.decision() != Decision.STARTED && old.decision() != e.decision())
                throw new IllegalStateException("Decisao global imutavel");
            if (!e.acknowledged().containsAll(old.acknowledged()))
                throw new IllegalStateException("Confirmacoes nao podem desaparecer");
        }
        Properties p = new Properties();
        p.setProperty("resultId", e.resultId());
        p.setProperty("sha256", e.sha256());
        p.setProperty("decision", e.decision().name());
        p.setProperty("updatedAt", Long.toString(e.updatedAt()));
        p.setProperty("participantCount", Integer.toString(e.participants().size()));
        for (int i = 0; i < e.participants().size(); i++) {
            String address = e.participants().get(i);
            p.setProperty("participant." + i, address);
            p.setProperty("ack." + i, Boolean.toString(e.acknowledged().contains(address)));
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile(root, ".journal-", ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                p.store(out, "Two-phase commit coordinator journal");
                out.flush();
            }
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.READ)) {
                channel.force(true);
            }
            // Nao fazer fallback para rename nao atomico: assumimos Linux local.
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e2) {
                throw new IllegalStateException("O volume nao suporta rename atomico", e2);
            }
            tmp = null;
            try (FileChannel directory = FileChannel.open(root, StandardOpenOption.READ)) {
                directory.force(true);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Falha na gravacao duravel do journal", ex);
        } finally {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
        }
    }

    @PreDestroy
    @Override public void close() {
        try {
            if (lock != null && lock.isValid()) lock.release();
            lockChannel.close();
        } catch (IOException e) {
            System.err.println("Nao foi possivel liberar lock do journal: " + e.getMessage());
        }
    }
}
