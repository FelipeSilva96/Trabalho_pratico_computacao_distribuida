"""Injecao CONTROLADA e opt-in de uma falha de worker antes de COMMIT local.

Uso exclusivamente em laboratorio e apenas no worker Windows:
  INJECT_2PC_COMMIT_CRASH_ONCE=1
  INJECT_2PC_FAULT_TOKEN=demo-commit-01

A marca e fsyncada no volume persistente ANTES do encerramento abrupto.
Apos ``docker start``, a mesma configuracao nao dispara novamente com esse token.
O codigo so e invocado ao receber CommitReplica, nunca em PREPARE ou ABORT.
"""
from __future__ import annotations

import os
import re
from pathlib import Path
from typing import Callable, Mapping

_TOKEN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{7,63}$")


def maybe_crash_before_commit(
    tx_id: str,
    worker_id: str,
    replica_dir: str,
    *,
    environ: Mapping[str, str] | None = None,
    terminate: Callable[[], None] | None = None,
) -> bool:
    """Retorna False se desativado/ja disparado; caso contrario encerra o processo.

    `terminate` permite testar a funcao sem matar o processo de testes.
    Na operacao real, nunca retorna apos disparar o encerramento.
    """
    env = os.environ if environ is None else environ
    if worker_id != "worker-windows" or env.get("INJECT_2PC_COMMIT_CRASH_ONCE") != "1":
        return False

    token = env.get("INJECT_2PC_FAULT_TOKEN", "")
    if not _TOKEN.fullmatch(token):
        raise ValueError("INJECT_2PC_FAULT_TOKEN deve ter 8-64 caracteres [A-Za-z0-9_-]")

    root = Path(replica_dir)
    root.mkdir(parents=True, exist_ok=True)
    marker = root / f".fault-commit-once-{token}.marker"

    # O_EXCL garante que uma segunda chamada simultanea nao reinjete a falha.
    try:
        fd = os.open(marker, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError:
        return False

    try:
        with os.fdopen(fd, "wb") as file:
            file.write(f"worker={worker_id}\ntx={tx_id}\n".encode("utf-8"))
            file.flush()
            os.fsync(file.fileno())
        # Garantir a durabilidade da ENTRADA do diretorio apos criar o marker.
        dfd = os.open(root, os.O_RDONLY)
        try:
            os.fsync(dfd)
        finally:
            os.close(dfd)
    except BaseException:
        # Em caso de falha na persistencia do marcador, NAO matar o worker.
        # Remover o marcador incompleto para permitir repetir com o mesmo token.
        marker.unlink(missing_ok=True)
        raise

    print(
        f"[FAULT-2PC] CRASH_ON_COMMIT_ONCE token={token} tx={tx_id} "
        f"marker={marker} (antes do COMMIT local)",
        flush=True,
    )
    if terminate is None:
        os._exit(77)  # Simula perda abrupta do processo/servidor gRPC.
    terminate()
    raise RuntimeError("O gancho de encerramento retornou indevidamente")
