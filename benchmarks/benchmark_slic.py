#!/usr/bin/env python3
"""Benchmarks reproduzíveis do SLIC distribuído, com/sem 2PC.

Somente biblioteca padrão. Executa uma requisição por vez para evitar
contaminação entre cenários; o servidor continua livre para processar tiles
concorrentemente.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import random
import statistics
import sys
import time
import urllib.error
import urllib.request
import uuid
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

SCENARIOS = (
    ("um_worker_sem_2pc", "single", "/api/segment-distributed", False),
    ("dois_workers_sem_2pc", "dual", "/api/segment-distributed", False),
    ("dois_workers_com_2pc", "dual", "/api/segment-transactional", True),
)
FIELDS = [
    "timestamp_utc", "scenario", "phase", "round", "order_in_round", "ok",
    "http_status", "request_ms", "read_ms", "end_to_end_ms",
    "server_segmentation_ms", "png_bytes", "sha256", "hash_equal_reference",
    "workers", "tiles", "transaction_id", "result_id", "decision",
    "transaction_status", "acknowledged_count", "replica_count", "error",
]


def now_utc():
    return datetime.now(timezone.utc).isoformat()


def request_bytes(url: str, *, body=None, content_type=None, timeout=120):
    headers = {}
    if content_type:
        headers["Content-Type"] = content_type
    request = urllib.request.Request(url, data=body, headers=headers, method="POST" if body is not None else "GET")
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = response.read()
            elapsed = (time.perf_counter() - started) * 1000
            return response.status, dict(response.headers), payload, elapsed
    except urllib.error.HTTPError as exc:
        details = exc.read()[:800].decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {exc.code}: {details}") from exc
    except (urllib.error.URLError, TimeoutError) as exc:
        raise RuntimeError(f"Falha de rede: {exc}") from exc


def multipart(image: bytes, input_name: str, segments: int, compactness: float, rows: int, cols: int):
    boundary = "----slic-benchmark-" + uuid.uuid4().hex
    body = bytearray()
    for name, value in (("segments", segments), ("compactness", compactness), ("rows", rows), ("cols", cols)):
        body.extend((f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n").encode("utf-8"))
    body.extend((
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{input_name}\"\r\n"
        "Content-Type: image/jpeg\r\n\r\n"
    ).encode("utf-8"))
    body.extend(image)
    body.extend(f"\r\n--{boundary}--\r\n".encode("utf-8"))
    return bytes(body), f"multipart/form-data; boundary={boundary}"


def check_health(url, expected_workers):
    status, _, raw, _ = request_bytes(url.rstrip("/") + "/api/workers", timeout=20)
    workers = json.loads(raw)
    if status != 200 or len(workers) != expected_workers or any(w.get("status") != "UP" for w in workers):
        raise RuntimeError(f"Preflight rejeitado: {url} esperados {expected_workers} workers UP, recebido: {workers}")
    print(f"[OK] {url}: {', '.join(w['workerId'] for w in workers)}")


def perform_case(scenario, host_url, body, content_type, timeout, expected_hash=None):
    name, _, route, transactional = scenario
    row = {field: "" for field in FIELDS}
    row.update(timestamp_utc=now_utc(), scenario=name, ok=False)
    start = time.perf_counter()
    try:
        status, headers, raw, request_ms = request_bytes(host_url.rstrip("/") + route, body=body, content_type=content_type, timeout=timeout)
        row["http_status"] = status
        row["request_ms"] = round(request_ms, 3)
        if not transactional:
            if not raw.startswith(b"\x89PNG\r\n\x1a\n"):
                raise ValueError("Resposta sem assinatura PNG")
            png = raw
            row["server_segmentation_ms"] = headers.get("X-Total-Time-Ms", "")
            row["workers"] = headers.get("X-Worker-Ids", "")
            row["tiles"] = headers.get("X-Tiles", "")
            row["read_ms"] = 0.0
        else:
            data = json.loads(raw)
            tx = data["transaction"]
            row["transaction_id"] = tx["transactionId"]
            row["result_id"] = tx["resultId"]
            row["decision"] = tx["decision"]
            row["transaction_status"] = tx["status"]
            row["acknowledged_count"] = tx["acknowledgedCount"]
            row["replica_count"] = tx["replicaCount"]
            row["server_segmentation_ms"] = data["segmentationMs"]
            row["workers"] = ",".join(data["workers"])
            row["tiles"] = data["tiles"]
            if tx["decision"] != "COMMIT" or tx["status"] != "COMMITTED" or tx["replicaCount"] != 2 or tx["acknowledgedCount"] != 2:
                raise RuntimeError(f"Transacao nao confirmou integralmente: {tx}")
            _, _, png, read_ms = request_bytes(host_url.rstrip("/") + f"/api/results/{tx['resultId']}", timeout=timeout)
            row["read_ms"] = round(read_ms, 3)
            if hashlib.sha256(png).hexdigest() != tx["sha256"]:
                raise ValueError("Hash da imagem recuperada diverge do registrado no 2PC")
        row["sha256"] = hashlib.sha256(png).hexdigest()
        row["png_bytes"] = len(png)
        if expected_hash:
            row["hash_equal_reference"] = row["sha256"] == expected_hash
            if not row["hash_equal_reference"]:
                raise ValueError(f"Hash divergente: esperado {expected_hash}, obtido {row['sha256']}")
        row["end_to_end_ms"] = round((time.perf_counter() - start) * 1000, 3)
        row["ok"] = True
    except Exception as exc:
        row["end_to_end_ms"] = round((time.perf_counter() - start) * 1000, 3)
        row["error"] = f"{type(exc).__name__}: {exc}"[:1200]
    return row


def percentile(values, p):
    series = sorted(values)
    if len(series) == 1:
        return series[0]
    index = (len(series) - 1) * (p / 100)
    low = int(index)
    high = min(low + 1, len(series) - 1)
    return series[low] + (series[high] - series[low]) * (index - low)


def describe(vals):
    return {
        "n": len(vals), "media_ms": round(statistics.mean(vals), 3),
        "mediana_ms": round(statistics.median(vals), 3),
        "desvio_padrao_ms": round(statistics.stdev(vals), 3) if len(vals) > 1 else None,
        "p95_ms": round(percentile(vals, 95), 3),
        "min_ms": round(min(vals), 3), "max_ms": round(max(vals), 3),
    }


def summarize(rows):
    grouped = defaultdict(list)
    failed = []
    for row in rows:
        if row["phase"] != "measurement":
            continue
        if row["ok"]:
            grouped[row["scenario"]].append(row)
        else:
            failed.append({"scenario": row["scenario"], "round": row["round"], "error": row["error"]})
    summary = {"generated_at_utc": now_utc(), "scenarios": {}, "failed_samples": failed}
    for scenario, records in grouped.items():
        summary["scenarios"][scenario] = {
            "client_request": describe([float(r["request_ms"]) for r in records]),
            "client_end_to_end": describe([float(r["end_to_end_ms"]) for r in records]),
            "server_segmentation": describe([float(r["server_segmentation_ms"]) for r in records]),
            "client_read": describe([float(r["read_ms"]) for r in records]),
            "all_same_sha256": len({r["sha256"] for r in records}) == 1,
        }
    a = summary["scenarios"].get("um_worker_sem_2pc")
    b = summary["scenarios"].get("dois_workers_sem_2pc")
    c = summary["scenarios"].get("dois_workers_com_2pc")
    if a and b:
        x = a["client_end_to_end"]["mediana_ms"]
        y = b["client_end_to_end"]["mediana_ms"]
        summary["speedup_one_vs_two_workers_client_e2e"] = round(x / y, 3) if y else None
    if b and c:
        x = b["client_end_to_end"]["mediana_ms"]
        y = c["client_end_to_end"]["mediana_ms"]
        summary["transactional_overhead_client_e2e_percent"] = round((y / x - 1) * 100, 2) if x else None
    return summary


def main():
    parser = argparse.ArgumentParser(description="Benchmark de tres cenarios SLIC, 1 vs 2 workers, sem/com 2PC.")
    parser.add_argument("--input", type=Path, required=True, help="Imagem JPEG identica em todos os testes")
    parser.add_argument("--dual-url", default="http://127.0.0.1:8080")
    parser.add_argument("--single-url", default="http://127.0.0.1:8081")
    parser.add_argument("--segments", type=int, default=500)
    parser.add_argument("--compactness", type=float, default=10.0)
    parser.add_argument("--rows", type=int, default=2)
    parser.add_argument("--cols", type=int, default=2)
    parser.add_argument("--warmups", type=int, default=2)
    parser.add_argument("--repeats", type=int, default=10)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--expected-sha256", default=None)
    parser.add_argument("--output-dir", type=Path, default=Path("benchmarks/resultados"))
    parser.add_argument("--skip-health", action="store_true", help="So para testes locais simulados")
    args = parser.parse_args()
    if args.repeats < 1 or args.warmups < 0:
        parser.error("--repeats >= 1 e --warmups >= 0")
    if not args.input.is_file():
        parser.error(f"Imagem ausente: {args.input}")
    if args.expected_sha256 is not None and (len(args.expected_sha256) != 64 or any(x not in "0123456789abcdef" for x in args.expected_sha256)):
        parser.error("--expected-sha256 requer 64 caracteres hexadecimais minusculos")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    csv_file = args.output_dir / "amostras.csv"
    json_file = args.output_dir / "resumo.json"
    input_bytes = args.input.read_bytes()
    body, content_type = multipart(input_bytes, args.input.name, args.segments, args.compactness, args.rows, args.cols)
    if not args.skip_health:
        check_health(args.dual_url, 2)
        check_health(args.single_url, 1)
    print(f"Imagem {args.input.name}: {len(input_bytes)} bytes; warmups={args.warmups}; medicoes={args.repeats} por cenario")
    rng = random.Random(args.seed)
    rows = []
    reference = args.expected_sha256
    with csv_file.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=FIELDS)
        writer.writeheader()
        for phase, rounds in (("warmup", args.warmups), ("measurement", args.repeats)):
            for round_num in range(1, rounds + 1):
                scenarios = list(SCENARIOS)
                rng.shuffle(scenarios)
                for order, scenario in enumerate(scenarios, 1):
                    host = args.single_url if scenario[1] == "single" else args.dual_url
                    row = perform_case(scenario, host, body, content_type, args.timeout, reference)
                    row.update(phase=phase, round=round_num, order_in_round=order)
                    if row["ok"] and reference is None:
                        reference = row["sha256"]
                        row["hash_equal_reference"] = True
                    rows.append(row)
                    writer.writerow(row)
                    stream.flush()
                    label = "OK" if row["ok"] else "FALHOU"
                    print(f"[{phase} {round_num}/{rounds}] {scenario[0]}: {label} e2e={row['end_to_end_ms']}ms hash={str(row['sha256'])[:12]} {row['error']}", flush=True)
    summary = summarize(rows)
    summary["metadata"] = {
        "image_file": args.input.name, "input_sha256": hashlib.sha256(input_bytes).hexdigest(),
        "expected_output_sha256": reference,
        "segments": args.segments, "compactness": args.compactness,
        "rows": args.rows, "cols": args.cols,
        "single_url": args.single_url, "dual_url": args.dual_url,
        "warmups_per_scenario": args.warmups, "measurements_per_scenario": args.repeats,
        "seed": args.seed,
        "method": "sequential randomized rounds; client wall-clock perf_counter; transaction includes GET result",
    }
    json_file.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("\nRESUMO (mediana de ponta a ponta por cenario):")
    for name, value in summary["scenarios"].items():
        print(f"  {name}: {value['client_end_to_end']['mediana_ms']:.1f}ms (n={value['client_end_to_end']['n']})")
    if "speedup_one_vs_two_workers_client_e2e" in summary:
        print("Speedup 1 worker / 2 workers sem 2PC:", summary["speedup_one_vs_two_workers_client_e2e"])
    if "transactional_overhead_client_e2e_percent" in summary:
        print("Sobrecusto 2PC + leitura (%):", summary["transactional_overhead_client_e2e_percent"])
    print("Amostras:", csv_file, "\nResumo:", json_file)
    if summary["failed_samples"]:
        print(f"ATENCAO: {len(summary['failed_samples'])} medicao(oes) falharam; rever logs e rede.", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
