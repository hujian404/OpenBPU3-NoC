#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RTL_DIR="${ROOT_DIR}/generated/rtl"
RTL_FALLBACK_DIR="${ROOT_DIR}/generated"
VERILATOR_DIR="${ROOT_DIR}/verilator-noc-lib/build"
MODULE_NAME="${NOC_MODULE_NAME:-OpenBPUNoC}"
TOP_NAME="${NOC_SHIM_TOP:-noc_top}"
NUM_INPUTS="${NOC_NUM_INPUTS:-80}"
NUM_OUTPUTS="${NOC_NUM_OUTPUTS:-64}"
DEST_BITS="${NOC_DEST_BITS:-7}"
VC_BITS="${NOC_VC_BITS:-1}"
DATA_BITS="${NOC_DATA_BITS:-53}"
PACKET_BITS="${NOC_PACKET_BITS:-64}"
CREDIT_WIDTH="${NOC_CREDIT_WIDTH:-5}"
RTL_FILE="${RTL_DIR}/${MODULE_NAME}.sv"
SKIP_CHISEL="${NOC_SKIP_CHISEL:-0}"
GENERATOR_MAIN="${NOC_GENERATOR_MAIN:-openbpu.NoCGenerator}"
METADATA_FILE="${ROOT_DIR}/generated/openbpu_noc_meta.env"

mkdir -p "${RTL_DIR}" "${VERILATOR_DIR}"
rm -rf "${VERILATOR_DIR}/obj_dir"

if [[ ! -f "${RTL_FILE}" && -f "${RTL_FALLBACK_DIR}/${MODULE_NAME}.sv" ]]; then
  RTL_FILE="${RTL_FALLBACK_DIR}/${MODULE_NAME}.sv"
fi

if [[ "${SKIP_CHISEL}" != "1" ]]; then
  echo "[build_noc] Generating Chisel/SystemVerilog"
  (
    cd "${ROOT_DIR}"
    if command -v sbt >/dev/null 2>&1; then
      sbt --batch "runMain ${GENERATOR_MAIN}"
    elif command -v mill >/dev/null 2>&1; then
      mill --no-server MyNoC.runMain "${GENERATOR_MAIN}"
    else
      echo "[build_noc] Neither sbt nor mill is available in PATH" >&2
      exit 1
    fi
  )
fi

if [[ -f "${METADATA_FILE}" ]]; then
  # shellcheck disable=SC1090
  source "${METADATA_FILE}"
  MODULE_NAME="${NOC_MODULE_NAME:-${MODULE_NAME}}"
  NUM_INPUTS="${NOC_NUM_INPUTS:-${NUM_INPUTS}}"
  NUM_OUTPUTS="${NOC_NUM_OUTPUTS:-${NUM_OUTPUTS}}"
  DEST_BITS="${NOC_DEST_BITS:-${DEST_BITS}}"
  VC_BITS="${NOC_VC_BITS:-${VC_BITS}}"
  DATA_BITS="${NOC_DATA_BITS:-${DATA_BITS}}"
  PACKET_BITS="${NOC_PACKET_BITS:-${PACKET_BITS}}"
  CREDIT_WIDTH="${NOC_CREDIT_WIDTH:-${CREDIT_WIDTH}}"
  RTL_FILE="${RTL_DIR}/${MODULE_NAME}.sv"
  if [[ ! -f "${RTL_FILE}" && -f "${RTL_FALLBACK_DIR}/${MODULE_NAME}.sv" ]]; then
    RTL_FILE="${RTL_FALLBACK_DIR}/${MODULE_NAME}.sv"
  fi
fi

if [[ ! -f "${RTL_FILE}" ]]; then
  echo "[build_noc] Could not find generated RTL for ${MODULE_NAME}" >&2
  exit 1
fi

echo "[build_noc] Generating shim top ${TOP_NAME}.sv"
echo "[build_noc] Using metadata: module=${MODULE_NAME} inputs=${NUM_INPUTS} outputs=${NUM_OUTPUTS} dest_bits=${DEST_BITS} vc_bits=${VC_BITS} data_bits=${DATA_BITS} packet_bits=${PACKET_BITS}"
python3 - "${ROOT_DIR}" "${RTL_DIR}" "${VERILATOR_DIR}" "${MODULE_NAME}" "${TOP_NAME}" \
  "${NUM_INPUTS}" "${NUM_OUTPUTS}" "${DEST_BITS}" "${VC_BITS}" "${DATA_BITS}" "${CREDIT_WIDTH}" <<'PY'
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
rtl_dir = pathlib.Path(sys.argv[2])
out_dir = pathlib.Path(sys.argv[3])
module_name = sys.argv[4]
top_name = sys.argv[5]
num_inputs = int(sys.argv[6])
num_outputs = int(sys.argv[7])
dest_bits = int(sys.argv[8])
vc_bits = int(sys.argv[9])
data_bits = int(sys.argv[10])
credit_width = int(sys.argv[11])

sv = []
flit_width = 2 + 1 + vc_bits + dest_bits + data_bits
sv.append(f"module {top_name} (")
sv.append("  input logic clk,")
sv.append("  input logic reset,")
sv.append(f"  input logic [{num_inputs - 1}:0] in_valid,")
sv.append(f"  output logic [{num_inputs - 1}:0] in_ready,")
sv.append(f"  input logic [{num_inputs * flit_width - 1}:0] in_packet,")
sv.append(f"  output logic [{num_outputs - 1}:0] out_valid,")
sv.append(f"  input logic [{num_outputs - 1}:0] out_ready,")
sv.append(f"  output logic [{num_outputs * flit_width - 1}:0] out_packet")
sv.append(");")
sv.append("")
sv.append(f"  logic [{num_inputs - 1}:0] sm_flit_ready;")
sv.append(f"  logic [{num_inputs * credit_width - 1}:0] sm_credit_in_0;")
if (1 << vc_bits) > 1:
    for vc in range(1, 1 << vc_bits):
        sv.append(f"  logic [{num_inputs * credit_width - 1}:0] sm_credit_in_{vc};")
sv.append(f"  logic [{num_outputs - 1}:0] l2_flit_valid;")
sv.append(f"  logic [{num_outputs * flit_width - 1}:0] l2_packet;")
sv.append(f"  logic [{num_outputs * credit_width - 1}:0] l2_credit_out_0;")
if (1 << vc_bits) > 1:
    for vc in range(1, 1 << vc_bits):
        sv.append(f"  logic [{num_outputs * credit_width - 1}:0] l2_credit_out_{vc};")
sv.append("")
sv.append(f"  {module_name} dut (")

connections = []
for idx in range(num_inputs):
    base = idx * flit_width
    connections.extend([
        f"    .clock(clk)",
        f"    .reset(reset)",
    ])
    break

for idx in range(num_inputs):
    base = idx * flit_width
    connections.extend([
        f"    .io_sm_{idx}_flit_ready(sm_flit_ready[{idx}])",
        f"    .io_sm_{idx}_flit_valid(in_valid[{idx}])",
        f"    .io_sm_{idx}_flit_bits_flitType(in_packet[{base + 1}:{base}])",
        f"    .io_sm_{idx}_flit_bits_isLast(in_packet[{base + 2}])",
        f"    .io_sm_{idx}_flit_bits_vc(in_packet[{base + 2 + vc_bits}:{base + 3}])",
        f"    .io_sm_{idx}_flit_bits_destId(in_packet[{base + 2 + vc_bits + dest_bits}:{base + 3 + vc_bits}])",
        f"    .io_sm_{idx}_flit_bits_data(in_packet[{base + 2 + vc_bits + dest_bits + data_bits}:{base + 3 + vc_bits + dest_bits}])",
    ])
    for vc in range(1 << vc_bits):
        connections.append(
            f"    .io_sm_{idx}_creditIn_{vc}(sm_credit_in_{vc}[{(idx + 1) * credit_width - 1}:{idx * credit_width}])"
        )
        connections.append(
            f"    .io_sm_{idx}_creditOut_{vc}({credit_width}'d{(1 << credit_width) - 1})"
        )

for idx in range(num_outputs):
    base = idx * flit_width
    connections.extend([
        f"    .io_l2_{idx}_flit_ready(out_ready[{idx}])",
        f"    .io_l2_{idx}_flit_valid(l2_flit_valid[{idx}])",
        f"    .io_l2_{idx}_flit_bits_flitType(l2_packet[{base + 1}:{base}])",
        f"    .io_l2_{idx}_flit_bits_isLast(l2_packet[{base + 2}])",
        f"    .io_l2_{idx}_flit_bits_vc(l2_packet[{base + 2 + vc_bits}:{base + 3}])",
        f"    .io_l2_{idx}_flit_bits_destId(l2_packet[{base + 2 + vc_bits + dest_bits}:{base + 3 + vc_bits}])",
        f"    .io_l2_{idx}_flit_bits_data(l2_packet[{base + 2 + vc_bits + dest_bits + data_bits}:{base + 3 + vc_bits + dest_bits}])",
    ])
    for vc in range(1 << vc_bits):
        connections.append(
            f"    .io_l2_{idx}_creditIn_{vc}({credit_width}'d{(1 << credit_width) - 1})"
        )
        connections.append(
            f"    .io_l2_{idx}_creditOut_{vc}(l2_credit_out_{vc}[{(idx + 1) * credit_width - 1}:{idx * credit_width}])"
        )

for idx, line in enumerate(connections):
    suffix = "," if idx != len(connections) - 1 else ""
    sv.append(f"{line}{suffix}")

sv.append("  );")
sv.append("")

for idx in range(num_inputs):
    sv.append(f"  assign in_ready[{idx}] = sm_flit_ready[{idx}];")

for idx in range(num_outputs):
    base = idx * flit_width
    sv.append(f"  assign out_valid[{idx}] = l2_flit_valid[{idx}];")
    sv.append(f"  assign out_packet[{base + 1}:{base}] = l2_packet[{base + 1}:{base}];")
    sv.append(f"  assign out_packet[{base + 2}] = l2_packet[{base + 2}];")
    sv.append(f"  assign out_packet[{base + 2 + vc_bits}:{base + 3}] = l2_packet[{base + 2 + vc_bits}:{base + 3}];")
    sv.append(f"  assign out_packet[{base + 2 + vc_bits + dest_bits}:{base + 3 + vc_bits}] = l2_packet[{base + 2 + vc_bits + dest_bits}:{base + 3 + vc_bits}];")
    sv.append(f"  assign out_packet[{base + 2 + vc_bits + dest_bits + data_bits}:{base + 3 + vc_bits + dest_bits}] = l2_packet[{base + 2 + vc_bits + dest_bits + data_bits}:{base + 3 + vc_bits + dest_bits}];")

sv.append("endmodule")
(out_dir / f"{top_name}.sv").write_text("\n".join(sv) + "\n")
PY

echo "[build_noc] Running Verilator"
verilator \
  --cc "${VERILATOR_DIR}/${TOP_NAME}.sv" \
  "${RTL_FILE}" \
  --top-module "${TOP_NAME}" \
  -I"$(dirname "${RTL_FILE}")" \
  -Mdir "${VERILATOR_DIR}/obj_dir"

echo "[build_noc] Building Verilator static libraries"
make -C "${VERILATOR_DIR}/obj_dir" -f "V${TOP_NAME}.mk" \
  "V${TOP_NAME}__ALL.a" \
  -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu)"

echo "[build_noc] Done"
