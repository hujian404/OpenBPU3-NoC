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
- GPGPU-Sim submodule changes are preserved as an in-repo patch snapshot and
  can now be auto-applied by the build script.

However, the current request-network backend is not yet performance-correct:

- the request path injects packets successfully,
- bounded Rodinia runs now deliver a substantial fraction of injected request
  packets, but still leave a large tail of undrained requests,
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
- NoC metadata is emitted to `generated/openbpu_noc_meta.env`
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

### Directed request-path probe

The repository now also includes a standalone Verilator probe:

```bash
NOC_MODE=single ACTIVE_SOURCES=1 PACKETS_PER_SOURCE=1 MAX_CYCLES=128 \
./scripts/run_noc_probe.sh
```

This is intended to reproduce request-network forward-progress behavior without
the full GPGPU-Sim or Rodinia stack.

## Observed Metrics

From the validated remote Ubuntu runs on `10.156.154.31`, using:

```bash
RODINIA_APP=backprop \
GPGPUSIM_NETWORK_MODE=3 \
GPGPUSIM_DEADLOCK_DETECT=0 \
GPGPUSIM_MAX_CYCLE=20000 \
OPENBPU_CONFIG_EXTRA='-openbpu_req_min_input_hold_cycles 3' \
./scripts/run_rodinia.sh local
```

and:

```bash
RODINIA_APP=backprop \
GPGPUSIM_NETWORK_MODE=3 \
GPGPUSIM_DEADLOCK_DETECT=0 \
GPGPUSIM_MAX_CYCLE=20000 \
OPENBPU_CONFIG_EXTRA='-openbpu_req_min_input_hold_cycles 5' \
./scripts/run_rodinia.sh local
```

Observed result for both `hold=3` and `hold=5`:

- `gpu_sim_cycle = 20000`
- `gpu_sim_insn = 1678208`
- `icnt_total_pkts_simt_to_mem = 1462`
- `icnt_total_pkts_mem_to_simt = 811`

OpenBPU backend request-network metrics:

- `Req_Network__packets_injected = 1462`
- `Req_Network__packets_delivered = 821`
- `Req_Network__avg_latency_cycles = 6878.2387`
- `Req_Network__avg_hops = 2.0000`
- `Req_Network__throughput_packets_per_cycle = 0.0411`

Reply fallback-network metrics:

- `Reply_Network__packets_injected = 812`
- `Reply_Network__packets_delivered = 811`
- `Reply_Network__avg_latency_cycles = 20.0000`
- `Reply_Network__avg_hops = 2.0000`
- `Reply_Network__throughput_packets_per_cycle = 0.0406`

Adapter state at the end of the bounded run:

- `outstanding_payloads = 642`
- `reordered_request_packets = 0`

Run-level notes:

- `hold=3` finished in about `39 sec`
- `hold=5` finished in about `41 sec`
- the two bounded runs produced effectively identical interconnect results
- increasing the request-side wrapper hold from `3` to `5` does not improve
  bounded `backprop` progress on the current server setup

## Probe Results

The standalone probe now provides a much shorter debug loop for TODO 1.

### Single-packet probe

Command:

```bash
NOC_MODE=single ACTIVE_SOURCES=1 PACKETS_PER_SOURCE=1 MAX_CYCLES=128 \
./scripts/run_noc_probe.sh
```

Observed result:

- `injected = 1`
- `delivered = 1`
- `undelivered = 0`
- `current_cycle = 18`
- `avg_latency_cycles = 18`
- `held_input_cycles = 2`
- `duplicate_output_flits_suppressed = 0`

### Light multi-source probe

Command:

```bash
NOC_MODE=roundrobin ACTIVE_SOURCES=4 PACKETS_PER_SOURCE=1 MAX_CYCLES=256 \
./scripts/run_noc_probe.sh
```

Observed result:

- `injected = 4`
- `delivered = 4`
- `undelivered = 0`
- `current_cycle = 72`
- `avg_latency_cycles = 45`
- `held_input_cycles = 16`
- `duplicate_output_flits_suppressed = 3`
- `repeated_output_cycles_suppressed = 6`

### Hotspot probe

Command:

```bash
NOC_MODE=hotspot ACTIVE_SOURCES=4 PACKETS_PER_SOURCE=4 MAX_CYCLES=512 \
./scripts/run_noc_probe.sh
```

Observed result:

- `injected = 16`
- `delivered = 9`
- `undelivered = 7`
- `current_cycle = 512`
- `avg_latency_cycles = 90.4444`
- `held_input_cycles = 40`
- `duplicate_output_flits_suppressed = 16`
- `repeated_output_cycles_suppressed = 12`

This is important because it reproduces the request-side delivery collapse in a
small standalone workload. That strongly suggests the main remaining bug is tied
to contention or sustained multi-packet injection, rather than basic single-hop
connectivity.

The new duplicate-suppression counter also shows that the wrapper is still
masking part of the raw RTL behavior by collapsing repeated output packet IDs.

### Remote probe compatibility note

The standalone probe was also exercised on the Ubuntu server after syncing the
repository. One script-level portability issue was found and fixed:

- `scripts/run_noc_probe.sh` no longer compiles `verilated_threads.cpp`
  unconditionally
- this avoids a build failure on the server's Verilator `4.204`, whose shipped
  runtime was not configured for `VL_THREADED`

This does not affect the main GPGPU-Sim integration path, but it is important
for keeping the standalone probe reproducible across macOS and Ubuntu hosts.

### Remote probe observations

After the script fix, the server-side standalone probe was re-run directly on
`10.156.154.31`.

Single-packet probe:

- `injected = 1`
- `delivered = 1`
- `current_cycle = 18`
- `avg_latency_cycles = 18`
- `held_input_cycles = 4`
- `duplicate_output_flits_suppressed = 0`

Remote hotspot hold sweep:

```bash
HOLDS='1 3 5' \
NOC_MODE=hotspot \
ACTIVE_SOURCES=4 \
PACKETS_PER_SOURCE=4 \
MAX_CYCLES=512 \
./scripts/run_noc_hold_sweep.sh
```

Observed result:

- `hold=1`:
  - `16 injected / 0 delivered`
- `hold=3`:
  - `16 injected / 16 delivered`
  - `duplicate_output_flits_suppressed = 27`
  - `held_input_cycles = 32`
- `hold=5`:
  - `16 injected / 16 delivered`
  - `duplicate_output_flits_suppressed = 27`
  - `held_input_cycles = 64`

This is a useful cautionary result:

- the remote standalone probe can be made to report full delivery under
  hotspot traffic,
- but that success still depends on substantial wrapper-side duplicate
  suppression,
- and it does not translate into better bounded Rodinia behavior.

So the probe remains useful as a targeted reproducer, but it cannot yet be read
as proof that the raw request RTL path is correct under load.

### Small hotspot sweep

Command:

```bash
NOC_MODE=hotspot \
ACTIVE_SOURCES_LIST='1 2 4' \
PACKETS_PER_SOURCE_LIST='1 2 4' \
MAX_CYCLES=512 \
./scripts/run_noc_probe_sweep.sh
```

Observed result:

- `1 source x 1/2 packets` delivers completely
- `1 source x 4 packets` degrades to `4 injected / 3 delivered`
- `2 sources x 1/2 packets` delivers completely
- `2 sources x 4 packets` degrades to `8 injected / 7 delivered`
- `4 sources x 1/2 packets` delivers completely
- `4 sources x 4 packets` degrades sharply to `16 injected / 8 delivered`

This suggests the current failure mode is burst-depth sensitive:

- light hotspot traffic remains functional
- sustained hotspot bursts trigger a delivery collapse
- the collapse appears throughput-limited rather than obviously unfair, because
  the `4x4` case delivered `2` packets from each of the `4` sources
- even in lighter passing cases, wrapper-side duplicate suppression is active,
  so "all delivered" at the probe layer does not yet prove the raw RTL path is clean

### Input-hold sweep

Command:

```bash
HOLDS='1 2 3 4 5 6' \
NOC_MODE=hotspot \
ACTIVE_SOURCES=4 \
PACKETS_PER_SOURCE=4 \
MAX_CYCLES=512 \
./scripts/run_noc_hold_sweep.sh
```

Observed result:

- `hold=1` does not deliver packets in the current wrapper/RTL combination
- `hold=3` reproduces the older baseline of `16 injected / 8 delivered`
- `hold=5` is now the repository default and improves the same case to `16 injected / 9 delivered`
- `hold=6` regresses again

This suggests the current request path is sensitive not only to burst depth but
also to how long the wrapper must present a stable input flit. The best current
local operating point is still heuristic rather than protocol-clean.

## Interpretation

The integration layer is now functioning end-to-end, but the request network is
not draining correctly.

The key conclusion is:

- packets are accepted into the OpenBPU request backend,
- hundreds of request packets now make it through under bounded `backprop`,
  but a similarly large number still remain outstanding at the stop point,
- reply traffic is not the dominant blocker,
- standalone probe success is still partially propped up by wrapper duplicate
  suppression,
- the request-side Verilator/RTL behavior is the primary remaining issue.

This strongly suggests that the next phase should focus on request-network
forward progress and delivery correctness, not on build integration.

## Known Issues

### 1. Request delivery remains incomplete under load

The main active bug is:

- `1462` request packets injected
- only `821` delivered by the bounded run
- `642` payloads remain outstanding at the stop point

This is still the main blocker for meaningful simulation studies, even though it
is a major improvement over the earlier `649 injected / 8 delivered` baseline.

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
- compare GPGPU-Sim bounded runs under `-openbpu_req_min_input_hold_cycles 3/5`
  to see whether the small probe-side gain survives full-system traffic

Completed this round:

- synced the current repository state to the Ubuntu server
- rebuilt Chisel, Verilator, wrapper, and GPGPU-Sim remotely
- ran bounded Rodinia `backprop` with `hold=3` and `hold=5`
- established that the bounded full-system behavior is unchanged between the
  two hold settings on the current server run

### TODO 2. Add a directed request-path microbenchmark

Goal:

- reproduce request collapse without the full complexity of Rodinia

Status:

- completed in initial form via `scripts/run_noc_probe.sh`

Next suggested work:

- extend the probe with per-source progress counters
- add destination skew / burst-length sweeps
- correlate probe failure thresholds with GPGPU-Sim bounded-run statistics

Completed this round:

- added per-source delivery counters
- added a small hotspot sweep harness
- added an input-hold sweep harness
- established that degradation begins around sustained `pps=4` bursts in the
  current tested range
- observed that `hold=5` slightly outperforms the older `hold=3` baseline for
  the local `4x4 hotspot` probe

### TODO 3. Strengthen Chisel regression coverage

Goal:

- catch single-flit and multi-source progress bugs earlier at RTL level

Status:

- partially completed by adding `SinglePulseEndToEndSpec` and a diagnostic
  `MultiSourceHotspotSpec`

Next suggested work:

- add multi-source scoreboard-based delivery tests
- add backpressure and credit-stress regression cases
- add a Chisel-side burst-hotspot scoreboard that mirrors the standalone probe

Completed this round:

- converted `MultiSourceHotspotSpec` into two `pendingUntilFixed` diagnostics
- reproduced that a natural one-cycle `2x2` hotspot burst still drops packets
- reproduced that a wrapper-compatible held-valid `2x2` burst can surface duplicate packets

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
