#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REMOTE_HOST="${REMOTE_HOST:-}"
REMOTE_DIR="${REMOTE_DIR:-~/openbpu-gpgpu}"
RODINIA_APP="${RODINIA_APP:-backprop}"
RODINIA_ARGS="${RODINIA_ARGS:-}"
GPGPUSIM_CONFIG_DIR="${GPGPUSIM_CONFIG_DIR:-${ROOT_DIR}/gpgpu-sim/configs/tested-cfgs/SM7_GV100}"
STATS_FILE="${STATS_FILE:-${ROOT_DIR}/run/${RODINIA_APP}_noc_stats.log}"
CUDA_INSTALL_PATH="${CUDA_INSTALL_PATH:-/usr/local/cuda-11.8}"
RODINIA_ROOT="${ROOT_DIR}/tests/cuda/gpu-rodinia-3.1"

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

  if [[ ! -d "${RODINIA_ROOT}" ]]; then
    echo "Missing Rodinia checkout at ${RODINIA_ROOT}" >&2
    exit 1
  fi

  # Patch Rodinia 3.1 for CUDA 11.x toolchains.
  sed -i.bak "s#^CUDA_DIR *=.*#CUDA_DIR = ${CUDA_INSTALL_PATH}#" "${RODINIA_ROOT}/common/make.config"
  sed -i.bak "s#^NV_OPENCL_DIR *=.*#NV_OPENCL_DIR = ${CUDA_INSTALL_PATH}#" "${RODINIA_ROOT}/common/make.config"
  if grep -q '^# *SM_VERSIONS' "${RODINIA_ROOT}/common/common.mk"; then
    sed -i.bak 's/^# *SM_VERSIONS.*/SM_VERSIONS := sm_35/' "${RODINIA_ROOT}/common/common.mk"
  elif grep -q '^SM_VERSIONS' "${RODINIA_ROOT}/common/common.mk"; then
    sed -i.bak 's/^SM_VERSIONS.*/SM_VERSIONS := sm_35/' "${RODINIA_ROOT}/common/common.mk"
  else
    printf '\nSM_VERSIONS := sm_35\n' >> "${RODINIA_ROOT}/common/common.mk"
  fi
  find "${RODINIA_ROOT}" -name Makefile -exec sed -i.bak 's/sm_13/sm_35/g' {} +

  local binary=""
  local build_dir=""
  local default_args=""
  case "${RODINIA_APP}" in
    backprop)
      build_dir="${RODINIA_ROOT}/cuda/backprop"
      binary="${build_dir}/backprop"
      default_args="4096"
      ;;
    bfs)
      build_dir="${RODINIA_ROOT}/cuda/bfs"
      binary="${build_dir}/bfs"
      ;;
    kmeans)
      build_dir="${RODINIA_ROOT}/cuda/kmeans"
      binary="${build_dir}/kmeans"
      ;;
    *)
      echo "Unsupported RODINIA_APP=${RODINIA_APP}. Use backprop, bfs or kmeans." >&2
      exit 1
      ;;
  esac

  mkdir -p "${build_dir}/bin/linux/cuda"
  (
    cd "${build_dir}"
    make clean
    make -j"$(getconf _NPROCESSORS_ONLN)"
  )

  if [[ ! -x "${binary}" ]]; then
    cat <<EOF
Rodinia binary not found: ${binary}
Build the workload on the Ubuntu host first following docs/GPGPU-Sim.md.
EOF
    exit 1
  fi

  export GPGPUSIM_ROOT="${ROOT_DIR}/gpgpu-sim"
  export CUDA_INSTALL_PATH
  export OPENBPU_NOC_STATS_FILE="${STATS_FILE}"
  export OPENBPU_NOC_PRINT_STATS=1
  # shellcheck disable=SC1091
  source "${ROOT_DIR}/gpgpu-sim/setup_environment" release

  cp "${GPGPUSIM_CONFIG_DIR}/gpgpusim.config" "$(dirname "${binary}")/gpgpusim.config"
  cp "${GPGPUSIM_CONFIG_DIR}"/accelwattch*.xml "$(dirname "${binary}")/"
  cp "${GPGPUSIM_CONFIG_DIR}"/config_volta*.icnt "$(dirname "${binary}")/"
  (
    cd "$(dirname "${binary}")"
    if [[ -n "${RODINIA_ARGS}" ]]; then
      "${binary}" ${RODINIA_ARGS}
    else
      "${binary}" ${default_args}
    fi
  ) | tee "${STATS_FILE}"

  echo "---- OpenBPU NoC summary ----"
  grep -E 'Req_Network_injected_packets_per_cycle|Reply_Network_injected_packets_per_cycle|gpgpu_simulation_time|GPGPU-Sim: \*\*\* exit detected \*\*\*' "${STATS_FILE}" || true
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
