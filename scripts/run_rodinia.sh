#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REMOTE_HOST="${REMOTE_HOST:-}"
REMOTE_DIR="${REMOTE_DIR:-~/openbpu-gpgpu}"
RODINIA_APP="${RODINIA_APP:-bfs}"
RODINIA_ARGS="${RODINIA_ARGS:-}"
GPGPUSIM_CONFIG_DIR="${GPGPUSIM_CONFIG_DIR:-${ROOT_DIR}/gpgpu-sim/configs/tested-cfgs/SM7_GV100}"
STATS_FILE="${STATS_FILE:-${ROOT_DIR}/run/${RODINIA_APP}_noc_stats.log}"

mkdir -p "$(dirname "${STATS_FILE}")"

sync_remote() {
  if [[ -z "${REMOTE_HOST}" ]]; then
    echo "REMOTE_HOST is required for remote execution" >&2
    exit 1
  fi
  rsync -az --delete \
    --exclude '.git' \
    --exclude 'target' \
    --exclude 'out' \
    "${ROOT_DIR}/" "${REMOTE_HOST}:${REMOTE_DIR}/"
}

remote_run() {
  ssh "${REMOTE_HOST}" \
    "cd ${REMOTE_DIR} && RODINIA_APP='${RODINIA_APP}' RODINIA_ARGS='${RODINIA_ARGS}' GPGPUSIM_CONFIG_DIR='${GPGPUSIM_CONFIG_DIR}' STATS_FILE='${STATS_FILE}' scripts/run_rodinia.sh local"
}

local_run() {
  if [[ ! -d "${ROOT_DIR}/gpgpu-sim" ]]; then
    echo "Missing gpgpu-sim checkout" >&2
    exit 1
  fi

  local binary=""
  case "${RODINIA_APP}" in
    bfs)
      binary="${ROOT_DIR}/tests/cuda/gpu-rodinia-3.1/cuda/bfs/bfs"
      ;;
    kmeans)
      binary="${ROOT_DIR}/tests/cuda/gpu-rodinia-3.1/cuda/kmeans/kmeans"
      ;;
    *)
      echo "Unsupported RODINIA_APP=${RODINIA_APP}. Use bfs or kmeans." >&2
      exit 1
      ;;
  esac

  if [[ ! -x "${binary}" ]]; then
    cat <<EOF
Rodinia binary not found: ${binary}
Build the workload on the Ubuntu host first following docs/GPGPU-Sim.md.
EOF
    exit 1
  fi

  export GPGPUSIM_ROOT="${ROOT_DIR}/gpgpu-sim"
  export OPENBPU_NOC_STATS_FILE="${STATS_FILE}"
  export OPENBPU_NOC_PRINT_STATS=1

  cp "${GPGPUSIM_CONFIG_DIR}/gpgpusim.config" "$(dirname "${binary}")/gpgpusim.config"
  (
    cd "$(dirname "${binary}")"
    "${binary}" ${RODINIA_ARGS}
  ) | tee "${STATS_FILE}"

  echo "---- OpenBPU NoC summary ----"
  grep -E 'avg_latency_cycles|avg_hops|throughput_packets_per_cycle|throughput_bytes_per_cycle' "${STATS_FILE}" || true
}

mode="${1:-local}"
case "${mode}" in
  local)
    local_run
    ;;
  remote)
    sync_remote
    remote_run
    ;;
  *)
    echo "Usage: $0 [local|remote]" >&2
    exit 1
    ;;
esac
