#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 1 ]; then
  echo "Uso: $0 imagem1.jpg [imagem2.jpg ...]"
  exit 1
fi

args=()
for file in "$@"; do
  args+=(-F "files=@${file}")
done

curl -sS -X POST "http://localhost:8080/api/classify" "${args[@]}"
echo
