#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${ROOT_DIR}/verilator-noc-lib/build/probe"
OBJ_DIR="${ROOT_DIR}/verilator-noc-lib/build/obj_dir"
VERILATOR_ROOT_DIR="${VERILATOR_ROOT:-$(verilator -V 2>/dev/null | sed -n 's/^    VERILATOR_ROOT     = //p' | head -n1)}"
METADATA_FILE="${ROOT_DIR}/generated/openbpu_noc_meta.env"
PROBE_BIN="${BUILD_DIR}/noc_request_probe"

NOC_MODE="${NOC_MODE:-roundrobin}"
ACTIVE_SOURCES="${ACTIVE_SOURCES:-4}"
PACKETS_PER_SOURCE="${PACKETS_PER_SOURCE:-1}"
MAX_CYCLES="${MAX_CYCLES:-5000}"
MIN_INPUT_HOLD_CYCLES="${MIN_INPUT_HOLD_CYCLES:-5}"
PACKET_SIZE="${PACKET_SIZE:-8}"
FIXED_HOPS="${FIXED_HOPS:-2}"

if [[ ! -f "${OBJ_DIR}/Vnoc_top.h" ]]; then
  echo "[run_noc_probe] Missing Verilator build. Run scripts/build_noc.sh first." >&2
  exit 1
fi

if [[ ! -f "${METADATA_FILE}" ]]; then
  echo "[run_noc_probe] Missing ${METADATA_FILE}. Run openbpu.NoCGenerator first." >&2
  exit 1
fi

# shellcheck disable=SC1090
source "${METADATA_FILE}"

mkdir -p "${BUILD_DIR}"

c++ -std=c++17 -O2 \
  -I"${ROOT_DIR}/interconnect-wrapper" \
  -I"${ROOT_DIR}/verilator-noc-lib" \
  -I"${OBJ_DIR}" \
  -I"${VERILATOR_ROOT_DIR}/include" \
  "${ROOT_DIR}/interconnect-wrapper/noc_if.cpp" \
  "${ROOT_DIR}/verilator-noc-lib/noc_verilator_wrapper.cpp" \
  "${ROOT_DIR}/verilator-noc-lib/noc_request_probe.cpp" \
  "${VERILATOR_ROOT_DIR}/include/verilated.cpp" \
  "${OBJ_DIR}/Vnoc_top__ALL.a" \
  -o "${PROBE_BIN}"

"${PROBE_BIN}" \
  --mode "${NOC_MODE}" \
  --num-inputs "${NOC_NUM_INPUTS}" \
  --num-outputs "${NOC_NUM_OUTPUTS}" \
  --packet-bits "${NOC_PACKET_BITS}" \
  --flit-data-bits "${NOC_DATA_BITS}" \
  --dest-bits "${NOC_DEST_BITS}" \
  --vc-bits "${NOC_VC_BITS}" \
  --credit-width "${NOC_CREDIT_WIDTH}" \
  --active-sources "${ACTIVE_SOURCES}" \
  --packets-per-source "${PACKETS_PER_SOURCE}" \
  --max-cycles "${MAX_CYCLES}" \
  --min-input-hold-cycles "${MIN_INPUT_HOLD_CYCLES}" \
  --packet-size "${PACKET_SIZE}" \
  --fixed-hops "${FIXED_HOPS}"
