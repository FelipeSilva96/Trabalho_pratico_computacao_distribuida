"""Persistencia local de resultados PNG (base para replicacao futura).

IMPORTANTE: Este modulo NAO implementa replicacao distribuida nem 2PC.
Uma replica e local a um worker/container e deve usar um volume exclusivo.
"""

from __future__ import annotations

import hashlib
import io
import os
import re
import sqlite3
import tempfile
import threading
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from PIL import Image, UnidentifiedImageError

_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
_MAX_PNG_BYTES = 16 * 1024 * 1024


class ReplicaError(RuntimeError):
    """Falha na integridade ou na operacao de persistencia."""


class ReplicaConflict(ReplicaError):
    """Identificador ja armazenado com conteudo diferente."""


class ReplicaCorrupted(ReplicaError):
    """Arquivo ausente ou alterado depois do registro persistente."""


@dataclass(frozen=True)
class StoredReplica:
    result_id: str
    sha256: str
    size_bytes: int
    stored_at: str


class ReplicaStore:
    """Armazena PNGs imutaveis com hash e catalogo SQLite.

    Usar uma instancia por processo worker e volume local proprio.
    `save` e `read` validam identificadores, imagem e checksum.
    """

    def __init__(self, root: str | os.PathLike[str] = "/app/data") -> None:
        self.root = Path(root)
        self.committed = self.root / "committed"
        self.db_file = self.root / "replica.db"
        self._lock = threading.RLock()
        self.committed.mkdir(parents=True, exist_ok=True)
        self._initialize_db()

    @contextmanager
    def _connection(self):
        con = sqlite3.connect(self.db_file, timeout=15)
        try:
            con.execute("PRAGMA busy_timeout=15000")
            con.execute("PRAGMA synchronous=FULL")
            with con:
                yield con
        finally:
            con.close()

    def _initialize_db(self) -> None:
        with self._connection() as con:
            con.execute("PRAGMA journal_mode=WAL")
            con.execute(
                """CREATE TABLE IF NOT EXISTS results (
                      result_id TEXT PRIMARY KEY,
                      sha256 TEXT NOT NULL,
                      size_bytes INTEGER NOT NULL,
                      stored_at TEXT NOT NULL
                )"""
            )

    @staticmethod
    def _validated_id(result_id: str) -> str:
        if not isinstance(result_id, str) or not _ID.fullmatch(result_id):
            raise ValueError("result_id invalido: use 1-128 caracteres [A-Za-z0-9._-], iniciando por letra ou numero")
        return result_id

    @staticmethod
    def _validated_png(data: bytes) -> bytes:
        if not isinstance(data, bytes) or not data or len(data) > _MAX_PNG_BYTES:
            raise ValueError("PNG deve ser bytes nao vazios de ate 16 MiB")
        try:
            with Image.open(io.BytesIO(data)) as im:
                if im.format != "PNG":
                    raise ValueError("Imagem precisa estar no formato PNG")
                im.verify()
        except (OSError, UnidentifiedImageError) as exc:
            raise ValueError("Arquivo PNG invalido") from exc
        return data

    def save(self, result_id: str, png: bytes) -> StoredReplica:
        """Salva uma unica replica; repeticao identica e idempotente.

        Arquivo atomico antes do metadado: uma falha pode deixar arquivo
        orfao, mas nunca um registro valido apontando para arquivo novo
        que ainda nao foi publicado. O orfao identico e reconciliado no
        proximo `save`. A operacao nao e uma transacao distribuida.
        """
        rid = self._validated_id(result_id)
        data = self._validated_png(png)
        digest = hashlib.sha256(data).hexdigest()
        location = self.committed / f"{rid}.png"
        with self._lock:
            with self._connection() as con:
                row = con.execute(
                    "SELECT sha256, size_bytes, stored_at FROM results WHERE result_id=?",
                    (rid,),
                ).fetchone()
                if row is not None:
                    if row[0] != digest or row[1] != len(data):
                        raise ReplicaConflict(f"Resultado {rid} ja existe com outro conteudo")
                    if not location.is_file() or hashlib.sha256(location.read_bytes()).hexdigest() != digest:
                        raise ReplicaCorrupted(f"Replica {rid} possui arquivo ausente ou corrompido")
                    return StoredReplica(rid, row[0], row[1], row[2])

                if location.exists():
                    if hashlib.sha256(location.read_bytes()).hexdigest() != digest:
                        raise ReplicaConflict(f"Arquivo orfao {rid} possui conteudo divergente")
                else:
                    tmp_name = None
                    try:
                        with tempfile.NamedTemporaryFile(
                            mode="wb", dir=self.committed, prefix=".writing-", suffix=".tmp", delete=False
                        ) as f:
                            tmp_name = f.name
                            f.write(data)
                            f.flush()
                            os.fsync(f.fileno())
                        os.replace(tmp_name, location)
                        tmp_name = None
                        self._fsync_dir(self.committed)
                    finally:
                        if tmp_name:
                            Path(tmp_name).unlink(missing_ok=True)

                now = datetime.now(timezone.utc).isoformat()
                con.execute(
                    "INSERT INTO results(result_id,sha256,size_bytes,stored_at) VALUES(?,?,?,?)",
                    (rid, digest, len(data), now),
                )
                return StoredReplica(rid, digest, len(data), now)

    def read(self, result_id: str) -> bytes:
        rid = self._validated_id(result_id)
        with self._lock:
            with self._connection() as con:
                row = con.execute(
                    "SELECT sha256, size_bytes FROM results WHERE result_id=?", (rid,)
                ).fetchone()
            if row is None:
                raise KeyError(rid)
            path = self.committed / f"{rid}.png"
            if not path.is_file():
                raise ReplicaCorrupted(f"Replica {rid} sem arquivo PNG")
            data = path.read_bytes()
            if len(data) != row[1] or hashlib.sha256(data).hexdigest() != row[0]:
                raise ReplicaCorrupted(f"Checksum divergente na replica {rid}")
            return data

    @staticmethod
    def _fsync_dir(path: Path) -> None:
        # Containers sao Linux: sincroniza a entrada do diretorio apos rename.
        fd = os.open(path, os.O_RDONLY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
