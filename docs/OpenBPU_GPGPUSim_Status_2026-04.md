# OpenBPU x GPGPU-Sim Integration Status (2026-04)

## Summary

This document records the current integration status of the Chisel-based
OpenBPU request NoC with GPGPU-Sim through a Verilator-generated C++ backend.

As of 2026-04-28, the end-to-end framework is partially operational:

- Chisel RTL can be generated successfully.
- Verilator can compile the OpenBPU NoC into a C++ model.
- GPGPU-Sim can link against the OpenBPU wrapper backend.
- Rodinia `backprop` can run on the remote Ubuntu server with
  `-network_mode 3`.
- The OpenBPU backend prints packet latency, hop count, and throughput stats.

However, the current request-network backend is not yet performance-correct:

- the request path injects packets successfully,
- only a very small fraction of injected request packets are delivered,
- the reply path currently relies on a software fallback NoC,
- the workload only progresses meaningfully under a bounded-cycle diagnostic
  run.

In short: the framework is now integrated and runnable, but the hardware-backed
request interconnect still needs correctness and throughput work before it can
be used for research-grade experiments.

## Repository Components

The relevant components are:

- `src/main/scala/openbpu/`
  - Chisel OpenBPU NoC RTL
- `verilator-noc-lib/`
  - Verilator wrapper around generated `Vnoc_top`
- `interconnect-wrapper/`
  - simulator-independent NoC API and GPGPU-Sim adapter
- `gpgpu-sim/`
  - upstream GPGPU-Sim distribution checkout, locally patched at the
    `icnt_wrapper` layer
- `tests/cuda/gpu-rodinia-3.1/`
  - Rodinia benchmark suite
- `scripts/`
  - build and run entrypoints

## Implemented Architecture

### 1. OpenBPU backend mode in GPGPU-Sim

`gpgpu-sim/src/gpgpu-sim/icnt_wrapper.*` now supports:

- `INTERSIM = 1`
- `LOCAL_XBAR = 2`
- `OPENBPU_VERILATOR = 3`

Selecting `-network_mode 3` enables the OpenBPU backend.

### 2. NoC abstraction

`interconnect-wrapper/noc_if.*` provides:

- `push(src, dst, packet)`
- `pop(node)`
- `cycle()`
- packet metadata:
  - `src_id`
  - `dst_id`
  - `size`
  - `type`
  - `packet_id`

The abstraction is independent from GPGPU-Sim internals.

### 3. Verilator wrapper

`verilator-noc-lib/noc_verilator_wrapper.*` wraps the Verilated `Vnoc_top`
model and provides:

- cycle stepping
- input packet presentation
- output capture
- packet tracking by `packet_id`
- duplicate output suppression

The current wrapper uses a conservative request-injection policy to match the
present RTL behavior.

### 4. GPGPU-Sim adapter

`interconnect-wrapper/gpgpu_sim_noc_adapter.*` maps:

- shader nodes to request-network sources
- memory sub-partitions to request-network destinations
- memory nodes to reply-network sources
- shader nodes to reply-network destinations

It also tracks:

- outstanding payload handles
- software-side reordered request deliveries

### 5. Current dual-network model

The current implementation is asymmetric:

- Request path:
  - real OpenBPU RTL via Verilator
- Reply path:
  - `FixedLatencyNoc` software fallback

This is intentional for now because the available RTL top-level is request-only
(`SM -> L2`) rather than a fully bidirectional interconnect.

## Remote Validation Environment

Validated on:

- host: `hujian@10.156.154.31`
- path: `/home/hujian/openbpu3-noc`
- OS: Ubuntu
- CUDA: `/usr/local/cuda-11.8`

Rodinia compatibility fixes already applied in the build flow:

- replace `sm_13` with `sm_35`
- update CUDA include and library paths to CUDA 11.8

## Verified Build Flow

### Full rebuild

```bash
cd /home/hujian/openbpu3-noc
export CUDA_INSTALL_PATH=/usr/local/cuda-11.8
export PATH=/usr/local/cuda-11.8/bin:/usr/local/bin:$PATH
export LD_LIBRARY_PATH=/usr/local/cuda-11.8/lib64:$LD_LIBRARY_PATH
NOC_FORCE_BUILD_NOC=1 ./scripts/build_sim.sh local
```

Verified results:

- Chisel generation succeeds
- Verilator model archive is generated
- wrapper static library is generated
- GPGPU-Sim links successfully against the wrapper and Verilator model

## Verified Runtime Flow

### Diagnostic bounded run

```bash
cd /home/hujian/openbpu3-noc
export CUDA_INSTALL_PATH=/usr/local/cuda-11.8
export PATH=/usr/local/cuda-11.8/bin:/usr/local/bin:$PATH
export LD_LIBRARY_PATH=/usr/local/cuda-11.8/lib64:$LD_LIBRARY_PATH
RODINIA_APP=backprop \
GPGPUSIM_NETWORK_MODE=3 \
GPGPUSIM_DEADLOCK_DETECT=0 \
GPGPUSIM_MAX_CYCLE=20000 \
./scripts/run_rodinia.sh local
```

This run is currently the most useful validation mode because it guarantees a
stats dump even when the hardware-backed request path stalls.

## Observed Metrics

From the 20,000-cycle bounded `backprop` run:

- `gpu_sim_cycle = 20000`
- `gpu_sim_insn = 803680`
- `gpu_ipc = 40.1840`
- `icnt_total_pkts_simt_to_mem = 649`
- `icnt_total_pkts_mem_to_simt = 8`

OpenBPU backend request-network metrics:

- `Req_Network__packets_injected = 649`
- `Req_Network__packets_delivered = 8`
- `Req_Network__avg_latency_cycles = 81.0000`
- `Req_Network__avg_hops = 2.0000`
- `Req_Network__throughput_packets_per_cycle = 0.0004`

Reply fallback-network metrics:

- `Reply_Network__packets_injected = 8`
- `Reply_Network__packets_delivered = 8`
- `Reply_Network__avg_latency_cycles = 20.0000`
- `Reply_Network__avg_hops = 2.0000`
- `Reply_Network__throughput_packets_per_cycle = 0.0004`

Adapter state at the end of the bounded run:

- `outstanding_payloads = 641`
- `reordered_request_packets = 0`

## Interpretation

The integration layer is now functioning end-to-end, but the request network is
not draining correctly.

The key conclusion is:

- packets are accepted into the OpenBPU request backend,
- a very small number make it through,
- reply traffic is not the dominant blocker,
- the request-side Verilator/RTL behavior is the primary remaining issue.

This strongly suggests that the next phase should focus on request-network
forward progress and delivery correctness, not on build integration.

## Known Issues

### 1. Request delivery collapse

The main active bug is:

- `649` request packets injected
- only `8` delivered by the bounded run

This is the main blocker for meaningful simulation studies.

### 2. Reply path is still software-modeled

The current backend is not yet a fully hardware-backed bidirectional
interconnect. Reply traffic still uses a fixed-latency software model.

### 3. `gpgpu-sim/` is a submodule with local modifications

The `icnt_wrapper` integration currently lives inside the `gpgpu-sim`
submodule worktree. That means:

- parent-repo commits do not automatically capture those inner changes,
- care is required when sharing or re-cloning the project,
- long-term maintenance would benefit from either:
  - a maintained fork of `gpgpu-sim_distribution`, or
  - a patch application workflow stored in this repository.

## Near-Term TODO

### TODO 1. Fix request-side forward progress

Goal:

- deliver a much larger fraction of injected request packets
- eliminate the large growth of `outstanding_payloads`

Suggested work:

- inspect `noc_verilator_wrapper` drive/accept semantics again
- validate whether one packet is being over-held or under-consumed
- compare packed flit fields against Chisel RTL expectations bit by bit
- instrument per-source and per-destination progress counters

### TODO 2. Add a directed request-path microbenchmark

Goal:

- reproduce request collapse without the full complexity of Rodinia

Suggested work:

- create a tiny C++ test or synthetic GPGPU-Sim traffic source
- inject a controlled number of request packets
- verify exact delivery counts at each memory destination

### TODO 3. Strengthen Chisel regression coverage

Goal:

- catch single-flit and multi-source progress bugs earlier at RTL level

Suggested work:

- add tests for one-shot valid pulses
- add scoreboard-based end-to-end delivery tests
- add multi-cycle backpressure and credit-stress tests

### TODO 4. Preserve GPGPU-Sim integration changes in a reproducible way

Goal:

- avoid losing submodule-local `icnt_wrapper` edits

Suggested work:

- either fork `gpgpu-sim_distribution` and point the submodule at that fork
- or store a patch series under `scripts/patches/` and apply it automatically

### TODO 5. Replace reply fallback with real RTL

Goal:

- move from asymmetric to research-grade bidirectional hardware modeling

Suggested work:

- implement reverse-direction reply NoC RTL, or
- expose a bidirectional top-level OpenBPU NoC interface

### TODO 6. Align to a more faithful GV100 research baseline

Goal:

- improve configuration fidelity before publishing experimental results

Suggested work:

- review whether the current 80-shader / 64-subpartition mapping is sufficient
- validate packet sizes and per-cycle injection assumptions
- confirm that `flit_size`, routing width, VC assumptions, and queue depths
  match the intended GV100-style study methodology

## Recommended Next Step

The highest-value next task is:

- debug and repair request-path delivery so that bounded runs no longer show
  `hundreds injected / single-digit delivered`

Once that is stable, the next meaningful milestone is:

- run `backprop` to completion under `network_mode 3`,
- then repeat with `bfs` or `kmeans`,
- then replace the reply fallback with real RTL.
