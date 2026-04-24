#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REMOTE_HOST="${REMOTE_HOST:-}"
REMOTE_DIR="${REMOTE_DIR:-~/openbpu-gpgpu}"
WRAPPER_BUILD_DIR="${ROOT_DIR}/interconnect-wrapper/build"

sync_remote() {
  if [[ -z "${REMOTE_HOST}" ]]; then
    echo "REMOTE_HOST is required for remote sync" >&2
    exit 1
  fi
  rsync -az --delete \
    --exclude '.git' \
    --exclude 'target' \
    --exclude 'out' \
    "${ROOT_DIR}/" "${REMOTE_HOST}:${REMOTE_DIR}/"
}

remote_build() {
  if [[ -z "${REMOTE_HOST}" ]]; then
    echo "REMOTE_HOST is required for remote build" >&2
    exit 1
  fi
  ssh "${REMOTE_HOST}" "cd ${REMOTE_DIR} && scripts/build_sim.sh local"
}

local_build() {
  mkdir -p "${WRAPPER_BUILD_DIR}"
  if [[ ! -d "${ROOT_DIR}/gpgpu-sim/.git" ]]; then
    cat <<EOF
gpgpu-sim submodule is not initialized.
Run:
  git submodule add https://github.com/gpgpu-sim/gpgpu-sim_distribution gpgpu-sim
and follow docs/GPGPU-Sim.md on the Ubuntu host.
EOF
    exit 1
  fi

  if [[ ! -e "${ROOT_DIR}/verilator-noc-lib/build/obj_dir/Vnoc_top.h" ]]; then
    "${ROOT_DIR}/scripts/build_noc.sh"
  fi

  c++ -std=c++17 -O2 -fPIC \
    -I"${ROOT_DIR}/interconnect-wrapper" \
    -I"${ROOT_DIR}/verilator-noc-lib" \
    -I"${ROOT_DIR}/verilator-noc-lib/build/obj_dir" \
    -I"${ROOT_DIR}/gpgpu-sim/src" \
    -c "${ROOT_DIR}/interconnect-wrapper/noc_if.cpp" \
    -o "${WRAPPER_BUILD_DIR}/noc_if.o"

  c++ -std=c++17 -O2 -fPIC \
    -I"${ROOT_DIR}/interconnect-wrapper" \
    -I"${ROOT_DIR}/verilator-noc-lib" \
    -I"${ROOT_DIR}/verilator-noc-lib/build/obj_dir" \
    -I"${ROOT_DIR}/gpgpu-sim/src" \
    -c "${ROOT_DIR}/interconnect-wrapper/gpgpu_sim_noc_adapter.cpp" \
    -o "${WRAPPER_BUILD_DIR}/gpgpu_sim_noc_adapter.o"

  c++ -std=c++17 -O2 -fPIC \
    -I"${ROOT_DIR}/interconnect-wrapper" \
    -I"${ROOT_DIR}/verilator-noc-lib" \
    -I"${ROOT_DIR}/verilator-noc-lib/build/obj_dir" \
    -I"${ROOT_DIR}/gpgpu-sim/src" \
    -I"${ROOT_DIR}/gpgpu-sim/libcuda" \
    -c "${ROOT_DIR}/verilator-noc-lib/noc_verilator_wrapper.cpp" \
    -o "${WRAPPER_BUILD_DIR}/noc_verilator_wrapper.o"

  ar rcs "${WRAPPER_BUILD_DIR}/libopenbpu_noc_wrapper.a" \
    "${WRAPPER_BUILD_DIR}/noc_if.o" \
    "${WRAPPER_BUILD_DIR}/gpgpu_sim_noc_adapter.o" \
    "${WRAPPER_BUILD_DIR}/noc_verilator_wrapper.o"

  cat <<EOF
Wrapper library built at:
  ${WRAPPER_BUILD_DIR}/libopenbpu_noc_wrapper.a

Minimal GPGPU-Sim hook points:
  1. Replace icnt_wrapper.{cc,h} backend creation with GpgpuSimNocAdapter.
  2. Keep existing icnt_has_buffer/icnt_push/icnt_pop/icnt_transfer call sites unchanged.
  3. Link libopenbpu_noc_wrapper.a into the GPGPU-Sim final link step.
EOF
}

mode="${1:-local}"
case "${mode}" in
  local)
    local_build
    ;;
  sync)
    sync_remote
    ;;
  remote)
    sync_remote
    remote_build
    ;;
  *)
    echo "Usage: $0 [local|sync|remote]" >&2
    exit 1
    ;;
esac
