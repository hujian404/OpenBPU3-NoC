#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REMOTE_HOST="${REMOTE_HOST:-}"
REMOTE_DIR="${REMOTE_DIR:-~/openbpu-gpgpu}"
WRAPPER_BUILD_DIR="${ROOT_DIR}/interconnect-wrapper/build"
OBJ_DIR="${ROOT_DIR}/verilator-noc-lib/build/obj_dir"
VERILATOR_ROOT_DIR="${VERILATOR_ROOT:-$(verilator -V 2>/dev/null | sed -n 's/^    VERILATOR_ROOT     = //p' | head -n1)}"
CUDA_INSTALL_PATH_DEFAULT="${CUDA_INSTALL_PATH_DEFAULT:-/usr/local/cuda-11.8}"

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
  if [[ ! -d "${ROOT_DIR}/gpgpu-sim/src" ]]; then
    cat <<EOF
gpgpu-sim submodule is not initialized.
Run:
  git submodule add https://github.com/gpgpu-sim/gpgpu-sim_distribution gpgpu-sim
and follow docs/GPGPU-Sim.md on the Ubuntu host.
EOF
    exit 1
  fi

  if [[ -f "${ROOT_DIR}/patches/gpgpu-sim-openbpu-integration.patch" ]]; then
    "${ROOT_DIR}/scripts/apply_gpgpu_sim_patch.sh"
  fi

  if [[ "${NOC_FORCE_BUILD_NOC:-0}" == "1" || ! -e "${ROOT_DIR}/verilator-noc-lib/build/obj_dir/Vnoc_top.h" ]]; then
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
    -I"${VERILATOR_ROOT_DIR}/include" \
    -I"${ROOT_DIR}/gpgpu-sim/src" \
    -I"${ROOT_DIR}/gpgpu-sim/libcuda" \
    -c "${ROOT_DIR}/verilator-noc-lib/noc_verilator_wrapper.cpp" \
    -o "${WRAPPER_BUILD_DIR}/noc_verilator_wrapper.o"

  c++ -std=c++17 -O2 -fPIC \
    -I"${VERILATOR_ROOT_DIR}/include" \
    -c "${VERILATOR_ROOT_DIR}/include/verilated.cpp" \
    -o "${WRAPPER_BUILD_DIR}/verilated_runtime.o"

  ar rcs "${WRAPPER_BUILD_DIR}/libopenbpu_noc_wrapper.a" \
    "${WRAPPER_BUILD_DIR}/noc_if.o" \
    "${WRAPPER_BUILD_DIR}/gpgpu_sim_noc_adapter.o" \
    "${WRAPPER_BUILD_DIR}/noc_verilator_wrapper.o" \
    "${WRAPPER_BUILD_DIR}/verilated_runtime.o"

  if [[ "${NOC_SKIP_GPGPUSIM_BUILD:-0}" == "1" ]]; then
    cat <<EOF
Wrapper library built at:
  ${WRAPPER_BUILD_DIR}/libopenbpu_noc_wrapper.a

Skipped the GPGPU-Sim build because NOC_SKIP_GPGPUSIM_BUILD=1.
EOF
    return 0
  fi

  if [[ ! -f "${OBJ_DIR}/Vnoc_top__ALL.a" ]]; then
    echo "Missing Verilator model archive under ${OBJ_DIR}" >&2
    exit 1
  fi

  (
    cd "${ROOT_DIR}/gpgpu-sim"
    export CUDA_INSTALL_PATH="${CUDA_INSTALL_PATH:-${CUDA_INSTALL_PATH_DEFAULT}}"
    export LD_LIBRARY_PATH="${LD_LIBRARY_PATH:-}"
    export OPENCL_REMOTE_GPU_HOST="${OPENCL_REMOTE_GPU_HOST:-}"
    set +e
    set +u
    source setup_environment release
    local_setup_status=$?
    set -u
    set -e
    if [[ "${local_setup_status}" -ne 0 ]]; then
      echo "[build_sim] setup_environment returned ${local_setup_status}; continuing because rsynced trees may not carry .git metadata" >&2
    fi
    make -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu)" \
      OPENBPU_NOC_WRAPPER_LIB="${WRAPPER_BUILD_DIR}/libopenbpu_noc_wrapper.a" \
      OPENBPU_NOC_VERILATOR_MODEL_LIB="${OBJ_DIR}/Vnoc_top__ALL.a"
  )

  cat <<EOF
Wrapper library built at:
  ${WRAPPER_BUILD_DIR}/libopenbpu_noc_wrapper.a

GPGPU-Sim build completed with:
  OPENBPU_NOC_WRAPPER_LIB=${WRAPPER_BUILD_DIR}/libopenbpu_noc_wrapper.a
  OPENBPU_NOC_VERILATOR_MODEL_LIB=${OBJ_DIR}/Vnoc_top__ALL.a
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
