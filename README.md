# OpenBPU NoC Research Integration

This repository builds a research-oriented GPU interconnect simulation flow
around a Chisel NoC, a Verilator-generated C++ model, and GPGPU-Sim.

The current end-to-end path is:

- Chisel RTL in `src/main/scala/openbpu`
- Verilog/SystemVerilog generation with `openbpu.NoCGenerator`
- Verilator model generation and C++ wrapping
- GPGPU-Sim backend integration behind `icnt_wrapper`
- Rodinia CUDA workloads for bounded and full-system validation

## Repository Layout

- `src/main/scala/openbpu/`
  - OpenBPU NoC RTL and generator code
- `src/test/scala/openbpu/`
  - Chisel regression tests and targeted diagnostics
- `interconnect-wrapper/`
  - simulator-independent NoC API and the GPGPU-Sim adapter
- `verilator-noc-lib/`
  - Verilator runtime wrapper and standalone request-path probe
- `gpgpu-sim/`
  - GPGPU-Sim distribution submodule
- `tests/cuda/gpu-rodinia-3.1/`
  - Rodinia benchmark submodule
- `scripts/`
  - build, integration, probe, and run entrypoints
- `docs/`
  - setup notes, integration details, status tracking, and run guides

## What Is Implemented

- A clean NoC abstraction in `interconnect-wrapper/noc_if.*`
  - `push(src, dst, packet)`
  - `pop(node, packet)`
  - `cycle()`
  - packet metadata and latency/hop/throughput accounting
- A Verilator-backed wrapper in `verilator-noc-lib/noc_verilator_wrapper.*`
- A GPGPU-Sim adapter in `interconnect-wrapper/gpgpu_sim_noc_adapter.*`
- A backend hook in `gpgpu-sim/src/gpgpu-sim/icnt_wrapper.*`
  - `-network_mode 3` selects the OpenBPU backend
- Build and run scripts for NoC generation, simulator build, Rodinia, and
  standalone NoC probes

## Current Status

The framework is integrated and runnable, but not yet research-complete.

Validated today:

- Chisel compile and regression flow
- Verilator NoC build flow
- GPGPU-Sim build with the OpenBPU wrapper
- Remote Ubuntu execution on the configured server account
- Rodinia `backprop` under `-network_mode 3`

Current limitation:

- the request path is attached to the Verilated OpenBPU RTL
- the reply path still uses a software fallback NoC
- bounded Rodinia runs still leave a large number of request packets
  outstanding under load
- standalone hotspot probes can show full delivery only with wrapper-side
  duplicate suppression, so request-path correctness is still an active topic

For the most recent measured results, see
[`docs/OpenBPU_GPGPUSim_Status_2026-04.md`](docs/OpenBPU_GPGPUSim_Status_2026-04.md).

## Chisel Environment

The RTL and test flow in this repository is based on the following toolchain:

- JDK: 17
- sbt: 1.11.4
- Scala: 2.13.12
- Chisel: 6.2.0
- chiseltest: 6.0.0
- mill: optional alternative frontend for Scala/Chisel tasks

The authoritative versions come from:

- `project/build.properties`
- `build.sbt`
- `build.sc`

Recommended local setup:

```bash
java -version
sbt --version
```

You should see a Java 17 runtime and sbt 1.11.x.

If you are setting up a fresh machine, make sure:

- `JAVA_HOME` points to a JDK 17 installation
- `sbt` is on `PATH`
- `verilator` is on `PATH` if you want to build the C++ NoC backend
- CUDA is only required for the full GPGPU-Sim and Rodinia flow, not for pure
  Chisel compile/test work

Minimal Chisel bring-up checklist:

```bash
git submodule update --init --recursive
sbt compile
sbt test
sbt "runMain openbpu.NoCGenerator"
```

Optional `mill` equivalents:

```bash
mill --no-server MyNoC.test
mill --no-server MyNoC.runMain openbpu.NoCGenerator
```

If `sbt` cannot resolve dependencies on a new machine, check:

- outbound network access to Maven repositories
- that the JDK is really Java 17, not Java 8 or Java 21
- that Scala/Chisel versions are not being overridden by a local global config

## Quick Start

### 1. Initialize submodules

```bash
git submodule update --init --recursive
```

### 2. Run Chisel compile and tests

```bash
sbt compile
sbt test
```

### 3. Generate RTL

```bash
sbt "runMain openbpu.NoCGenerator"
```

### 4. Build the Verilator NoC model

```bash
./scripts/build_noc.sh
```

### 5. Build GPGPU-Sim with the OpenBPU wrapper

```bash
./scripts/build_sim.sh local
```

### 6. Run a bounded Rodinia validation

```bash
RODINIA_APP=backprop \
GPGPUSIM_NETWORK_MODE=3 \
GPGPUSIM_DEADLOCK_DETECT=0 \
GPGPUSIM_MAX_CYCLE=20000 \
./scripts/run_rodinia.sh local
```

## Standalone NoC Debugging

The repository includes a standalone Verilator probe that exercises the request
path without the full GPGPU-Sim stack.

Single-packet probe:

```bash
NOC_MODE=single ACTIVE_SOURCES=1 PACKETS_PER_SOURCE=1 MAX_CYCLES=128 \
./scripts/run_noc_probe.sh
```

Hotspot sweep:

```bash
NOC_MODE=hotspot \
ACTIVE_SOURCES_LIST='1 2 4' \
PACKETS_PER_SOURCE_LIST='1 2 4' \
MAX_CYCLES=512 \
./scripts/run_noc_probe_sweep.sh
```

Input-hold sweep:

```bash
HOLDS='1 3 5' \
NOC_MODE=hotspot \
ACTIVE_SOURCES=4 \
PACKETS_PER_SOURCE=4 \
MAX_CYCLES=512 \
./scripts/run_noc_hold_sweep.sh
```

## Remote Ubuntu Workflow

The main validated deployment target is a remote Ubuntu host with CUDA 11.8.

Typical flow:

1. Sync the repository to the server.
2. Build the NoC and GPGPU-Sim on the server.
3. Run bounded Rodinia workloads and compare interconnect stats.

Typical SSH entry:

```bash
ssh <your-remote-user>@<your-remote-host>
```

The server-side build/run details and measured results are tracked in:

- [`docs/GPGPU-Sim.md`](docs/GPGPU-Sim.md)
- [`docs/GPGPU-Sim_OpenBPU_Integration.md`](docs/GPGPU-Sim_OpenBPU_Integration.md)
- [`docs/测试运行命令.md`](docs/%E6%B5%8B%E8%AF%95%E8%BF%90%E8%A1%8C%E5%91%BD%E4%BB%A4.md)

## Important Notes

- This repository intentionally keeps most GPGPU-Sim integration changes at the
  wrapper boundary instead of rewriting simulator internals.
- The `gpgpu-sim/` submodule may have a locally patched worktree; the
  reproducible integration snapshot is also preserved in
  `patches/gpgpu-sim-openbpu-integration.patch`.
- Rodinia 3.1 needs CUDA 11.x compatibility adjustments such as replacing
  `sm_13` with `sm_35`. The provided scripts automate those changes.

## Recommended Next Steps

- Improve request-path forward progress under sustained hotspot traffic
- Reduce or eliminate wrapper-side duplicate suppression
- Replace the software reply fallback with a real RTL-backed reverse path
- Tighten GV100-faithful configuration assumptions before research reporting
