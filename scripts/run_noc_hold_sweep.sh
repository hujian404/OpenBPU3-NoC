#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOLDS="${HOLDS:-1 2 3 4 5 6}"
NOC_MODE="${NOC_MODE:-hotspot}"
ACTIVE_SOURCES="${ACTIVE_SOURCES:-4}"
PACKETS_PER_SOURCE="${PACKETS_PER_SOURCE:-4}"
MAX_CYCLES="${MAX_CYCLES:-512}"

printf '%-8s %-10s %-10s %-12s %-12s %-12s %-12s\n' \
  "hold" "injected" "delivered" "undelivered" "throughput" "dup_supp" "held_cycles"

for hold in ${HOLDS}; do
  set +e
  output="$(
    cd "${ROOT_DIR}" && \
    MIN_INPUT_HOLD_CYCLES="${hold}" \
    NOC_MODE="${NOC_MODE}" \
    ACTIVE_SOURCES="${ACTIVE_SOURCES}" \
    PACKETS_PER_SOURCE="${PACKETS_PER_SOURCE}" \
    MAX_CYCLES="${MAX_CYCLES}" \
    ./scripts/run_noc_probe.sh
  )"
  status=$?
  set -e

  injected="$(printf '%s\n' "${output}" | sed -n 's/^injected=//p')"
  delivered="$(printf '%s\n' "${output}" | sed -n 's/^delivered=//p')"
  undelivered="$(printf '%s\n' "${output}" | sed -n 's/^undelivered=//p')"
  throughput="$(printf '%s\n' "${output}" | sed -n 's/^throughput_packets_per_cycle=//p')"
  dup_supp="$(printf '%s\n' "${output}" | sed -n 's/^duplicate_output_flits_suppressed=//p')"
  held_cycles="$(printf '%s\n' "${output}" | sed -n 's/^held_input_cycles=//p')"

  printf '%-8s %-10s %-10s %-12s %-12s %-12s %-12s' \
    "${hold}" "${injected:-?}" "${delivered:-?}" "${undelivered:-?}" \
    "${throughput:-?}" "${dup_supp:-?}" "${held_cycles:-?}"
  if [[ ${status} -ne 0 ]]; then
    printf '  (incomplete)\n'
  else
    printf '\n'
  fi
done
