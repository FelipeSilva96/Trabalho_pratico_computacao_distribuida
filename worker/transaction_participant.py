"""Participante persistente do 2PC (sem coordenador global).

Garantias locais:
- PREPARE registra bytes e voto de forma duravel antes de responder;
- COMMIT/ABORT sao idempotentes e mutuamente exclusivos;
- falhas apos salvar o PNG e antes de marcar COMMITTED recuperam por retry;
- PREPARED nunca e abortado automaticamente so porque houve timeout;
- leituras publicas so incluem transacoes COMMITTED.

Nao e, sozinho, um protocolo atomico entre varias replicas. O coordenador
precisa registrar decisao global duravel, recupera-la e reemitir COMMIT/ABORT.
"""
from __future__ import annotations

import hashlib
import os
import re
import sqlite3
import tempfile
import threading
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path

from replica_store import ReplicaStore, ReplicaConflict, ReplicaCorrupted

_SHA256 = re.compile(r"^[0-9a-f]{64}$")


class TransactionConflict(RuntimeError):
    pass


class TransactionCorrupted(RuntimeError):
    pass


class TwoPhaseParticipant:
    def __init__(self, root: str | os.PathLike[str] = "/app/data"):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.prepared_dir = self.root / "prepared"
        self.prepared_dir.mkdir(parents=True, exist_ok=True)
        self.db_path = self.root / "participant.db"
        self.store = ReplicaStore(self.root)
        self._lock = threading.RLock()
        self._init_db()

    @contextmanager
    def _db(self):
        con = sqlite3.connect(self.db_path, timeout=15)
        try:
            con.execute("PRAGMA busy_timeout=15000")
            con.execute("PRAGMA synchronous=FULL")
            with con:
                yield con
        finally:
            con.close()

    def _init_db(self):
        with self._db() as con:
            con.execute("PRAGMA journal_mode=WAL")
            con.execute("""CREATE TABLE IF NOT EXISTS transactions(
                tx_id TEXT PRIMARY KEY,
                result_id TEXT UNIQUE,
                sha256 TEXT,
                size_bytes INTEGER,
                state TEXT NOT NULL CHECK(state IN ('PREPARED','COMMITTED','ABORTED')),
                updated_at TEXT NOT NULL
            )""")

    @staticmethod
    def _now():
        return datetime.now(timezone.utc).isoformat()

    def _temp_location(self, tx_id):
        return self.prepared_dir / f"{tx_id}.png"

    @staticmethod
    def _check_id(value):
        return ReplicaStore._validated_id(value)

    @staticmethod
    def _digest(png: bytes):
        return hashlib.sha256(png).hexdigest()

    @staticmethod
    def _sync_directory(path):
        ReplicaStore._fsync_dir(path)

    def status(self, tx_id):
        tx_id = self._check_id(tx_id)
        with self._lock, self._db() as con:
            r = con.execute("SELECT result_id,sha256,state FROM transactions WHERE tx_id=?", (tx_id,)).fetchone()
        return {"tx_id":tx_id,"result_id":r[0] or "","sha256":r[1] or "","state":r[2]} if r else {"tx_id":tx_id,"result_id":"","sha256":"","state":"UNKNOWN"}

    def prepare(self, tx_id, result_id, png, expected_sha256):
        tx_id = self._check_id(tx_id)
        result_id = self._check_id(result_id)
        data = ReplicaStore._validated_png(png)
        digest = self._digest(data)
        if not _SHA256.fullmatch(expected_sha256) or expected_sha256 != digest:
            raise ValueError("sha256 esperado invalido ou divergente")
        path = self._temp_location(tx_id)
        with self._lock:
            with self._db() as con:
                previous = con.execute("SELECT result_id,sha256,size_bytes,state FROM transactions WHERE tx_id=?", (tx_id,)).fetchone()
                if previous is not None:
                    if previous[:3] != (result_id,digest,len(data)) or previous[3] == "ABORTED":
                        raise TransactionConflict("Transacao existente com dados diferentes ou abortada")
                    if previous[3] == "PREPARED":
                        if not path.is_file() or self._digest(path.read_bytes()) != digest:
                            raise TransactionCorrupted("Dados da preparacao ausentes ou corrompidos")
                    return previous[3]
                collision = con.execute("SELECT tx_id FROM transactions WHERE result_id=?", (result_id,)).fetchone()
                if collision:
                    raise TransactionConflict("result_id ja associado a outra transacao")
            # Evita preparar algo que conflita com resultado gravado fora deste protocolo.
            try:
                existing = self.store.read(result_id)
            except KeyError:
                existing = None
            if existing is not None and self._digest(existing) != digest:
                raise TransactionConflict("Resultado existente possui conteudo diferente")
            # Primeiro persiste os bytes. Depois grava o voto PREPARED no SQLite.
            # Caso caia entre os dois, sobra apenas um orfao nao visivel.
            if path.exists():
                if self._digest(path.read_bytes()) != digest:
                    raise TransactionConflict("Arquivo de preparacao orfao diverge")
            else:
                tmp = None
                try:
                    with tempfile.NamedTemporaryFile(mode="wb",dir=self.prepared_dir,prefix=".writing-",suffix=".tmp",delete=False) as f:
                        tmp = f.name
                        f.write(data)
                        f.flush()
                        os.fsync(f.fileno())
                    os.replace(tmp,path)
                    tmp = None
                    self._sync_directory(self.prepared_dir)
                finally:
                    if tmp:
                        Path(tmp).unlink(missing_ok=True)
            with self._db() as con:
                con.execute("INSERT INTO transactions(tx_id,result_id,sha256,size_bytes,state,updated_at) VALUES(?,?,?,?,?,?)",
                            (tx_id,result_id,digest,len(data),"PREPARED",self._now()))
            return "PREPARED"

    def commit(self, tx_id):
        tx_id = self._check_id(tx_id)
        with self._lock:
            with self._db() as con:
                row = con.execute("SELECT result_id,sha256,size_bytes,state FROM transactions WHERE tx_id=?", (tx_id,)).fetchone()
            if row is None:
                raise KeyError(tx_id)
            rid,digest,size,state = row
            if state == "ABORTED":
                raise TransactionConflict("Nao pode confirmar transacao abortada")
            if state == "COMMITTED":
                return "COMMITTED"
            path = self._temp_location(tx_id)
            if not path.is_file():
                raise TransactionCorrupted("Arquivo PREPARED ausente")
            data = path.read_bytes()
            if len(data) != size or self._digest(data) != digest:
                raise TransactionCorrupted("Dados PREPARED corrompidos")
            # Idempotencia: se cair apos store.save e antes do UPDATE, retry e seguro.
            self.store.save(rid,data)
            with self._db() as con:
                con.execute("UPDATE transactions SET state='COMMITTED',updated_at=? WHERE tx_id=? AND state='PREPARED'",(self._now(),tx_id))
            path.unlink(missing_ok=True)
            self._sync_directory(self.prepared_dir)
            return "COMMITTED"

    def abort(self, tx_id):
        tx_id = self._check_id(tx_id)
        with self._lock:
            with self._db() as con:
                row = con.execute("SELECT state FROM transactions WHERE tx_id=?",(tx_id,)).fetchone()
                if row is None:
                    # Tombstone duravel: prepare tardio sera rejeitado.
                    con.execute("INSERT INTO transactions(tx_id,state,updated_at) VALUES(?,?,?)",(tx_id,"ABORTED",self._now()))
                elif row[0] == "COMMITTED":
                    raise TransactionConflict("Nao pode abortar transacao confirmada")
                elif row[0] == "PREPARED":
                    con.execute("UPDATE transactions SET state='ABORTED',updated_at=? WHERE tx_id=?",(self._now(),tx_id))
            self._temp_location(tx_id).unlink(missing_ok=True)
            self._sync_directory(self.prepared_dir)
            return "ABORTED"

    def read_committed(self, result_id):
        rid = self._check_id(result_id)
        with self._lock:
            with self._db() as con:
                row = con.execute("SELECT sha256 FROM transactions WHERE result_id=? AND state='COMMITTED'",(rid,)).fetchone()
            if row is None:
                raise KeyError(rid)
            data = self.store.read(rid)
            if self._digest(data) != row[0]:
                raise TransactionCorrupted("Hash nao corresponde a transacao confirmada")
            return data
