#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ACTIVE_SOURCES_LIST="${ACTIVE_SOURCES_LIST:-1 2 4 8}"
PACKETS_PER_SOURCE_LIST="${PACKETS_PER_SOURCE_LIST:-1 2 4 8}"
NOC_MODE="${NOC_MODE:-hotspot}"
MAX_CYCLES="${MAX_CYCLES:-1024}"

printf "mode=%s max_cycles=%s\n" "${NOC_MODE}" "${MAX_CYCLES}"
printf "%-8s %-8s %-10s %-10s %-12s %-12s\n" \
  "sources" "pps" "injected" "delivered" "undelivered" "throughput"

for sources in ${ACTIVE_SOURCES_LIST}; do
  for pps in ${PACKETS_PER_SOURCE_LIST}; do
    set +e
    output="$(
      ACTIVE_SOURCES="${sources}" \
      PACKETS_PER_SOURCE="${pps}" \
      NOC_MODE="${NOC_MODE}" \
      MAX_CYCLES="${MAX_CYCLES}" \
      "${ROOT_DIR}/scripts/run_noc_probe.sh" 2>&1
    )"
    status=$?
    set -e
    injected="$(printf "%s\n" "${output}" | sed -n 's/^injected=//p')"
    delivered="$(printf "%s\n" "${output}" | sed -n 's/^delivered=//p')"
    undelivered="$(printf "%s\n" "${output}" | sed -n 's/^undelivered=//p')"
    throughput="$(printf "%s\n" "${output}" | sed -n 's/^throughput_packets_per_cycle=//p')"
    if [[ -z "${injected}" ]]; then
      injected="err(${status})"
      delivered="-"
      undelivered="-"
      throughput="-"
    fi
    printf "%-8s %-8s %-10s %-10s %-12s %-12s\n" \
      "${sources}" "${pps}" "${injected}" "${delivered}" "${undelivered}" "${throughput}"
  done
done
