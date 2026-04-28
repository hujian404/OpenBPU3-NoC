#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PATCH_FILE="${ROOT_DIR}/patches/gpgpu-sim-openbpu-integration.patch"
GPGPUSIM_DIR="${ROOT_DIR}/gpgpu-sim"

if [[ ! -d "${GPGPUSIM_DIR}" ]]; then
  echo "Missing gpgpu-sim checkout at ${GPGPUSIM_DIR}" >&2
  exit 1
fi

if [[ ! -f "${PATCH_FILE}" ]]; then
  echo "Missing patch file ${PATCH_FILE}" >&2
  exit 1
fi

(
  cd "${GPGPUSIM_DIR}"
  if git apply --reverse --check "${PATCH_FILE}" >/dev/null 2>&1; then
    echo "OpenBPU GPGPU-Sim patch is already present."
    exit 0
  fi
  git apply --check "${PATCH_FILE}"
  git apply "${PATCH_FILE}"
  echo "Applied OpenBPU GPGPU-Sim patch:"
  echo "  ${PATCH_FILE}"
)
