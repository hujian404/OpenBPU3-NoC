# OpenBPU NoC Integration Hook Points

This repository keeps the GPGPU-Sim change surface intentionally small and now
implements the backend swap directly behind `icnt_wrapper`.

## Current hook point

Upstream GPGPU-Sim already routes all interconnect traffic through the classic
wrapper API:

- `icnt_has_buffer(...)`
- `icnt_push(...)`
- `icnt_pop(...)`
- `icnt_transfer()`

Those symbols live in:

- `gpgpu-sim/src/gpgpu-sim/icnt_wrapper.h`
- `gpgpu-sim/src/gpgpu-sim/icnt_wrapper.cc`

The current integration keeps all existing shader, memory-partition, and GPU
cycle call sites untouched. Only the backend selection inside `icnt_wrapper`
changes.

## Implemented backend mode

`icnt_wrapper.h` now exposes:

```cpp
enum network_mode {
  INTERSIM = 1,
  LOCAL_XBAR = 2,
  OPENBPU_VERILATOR = 3,
  N_NETWORK_MODE
};
```

Selecting `-network_mode 3` enables the OpenBPU backend.

## Backend structure

`OPENBPU_VERILATOR` currently does this:

- Request path:
  - GPGPU-Sim request packets (`cluster -> memory subpartition`) go through
    `openbpu::NocVerilatorWrapper`
  - The wrapper drives the Verilated `Vnoc_top` cycle by cycle
- Reply path:
  - Because the current RTL top is request-direction only (`SM -> L2`) and has
    fixed `80 -> 64` ports, replies cannot yet be faithfully driven through the
    same Verilated model
  - Replies therefore go through an explicit `FixedLatencyNoc` fallback inside
    `icnt_wrapper.cc`

This is a deliberate intermediate step: request traffic is already attached to
the hardware model, while reply traffic remains software-modeled until a reverse
NoC RTL is available.

## Adapter details

`openbpu::GpgpuSimNocAdapter` now performs:

- Global-node to local-port mapping
  - request source: `shader_id`
  - request destination: `mem2device(subpartition) - n_shader`
  - reply source: `mem2device(subpartition) - n_shader`
  - reply destination: `shader_id`
- `mem_fetch` type classification
  - `READ_REQUEST -> kRead`
  - `WRITE_REQUEST -> kWrite`
  - `READ_REPLY / WRITE_ACK -> kReply`
- `icnt_pop(node)` demux
  - shader nodes pop from reply NoC
  - memory nodes pop from request NoC

One subtle GPGPU-Sim compatibility point is preserved intentionally:

- `icnt_has_buffer(...)` only receives `(input, size)` from upstream
- Therefore OpenBPU admission control must remain source-side only at the
  wrapper boundary
- Destination-specific legality is enforced on `push(...)`, not on
  `has_buffer(...)`

## Statistics now exposed

The OpenBPU backend prints explicit research-facing metrics from `noc_if`:

- `Req_Network_avg_latency_cycles`
- `Req_Network_avg_hops`
- `Req_Network_throughput_packets_per_cycle`
- `Reply_Network_avg_latency_cycles`
- `Reply_Network_avg_hops`
- `Reply_Network_throughput_packets_per_cycle`

The generic `noc_if` stat dump is also printed afterwards.

## Build linkage

The current intended build flow is:

1. `scripts/build_noc.sh`
   - generates `noc_top.sv`
   - runs Verilator
   - builds `verilator-noc-lib/build/obj_dir/Vnoc_top__ALL.a`
2. `scripts/build_sim.sh`
   - compiles `noc_if.cpp`
   - compiles `gpgpu_sim_noc_adapter.cpp`
   - compiles `noc_verilator_wrapper.cpp`
   - compiles Verilator runtime `verilated.cpp`
   - archives them into `interconnect-wrapper/build/libopenbpu_noc_wrapper.a`
   - builds GPGPU-Sim with:
     - `OPENBPU_NOC_WRAPPER_LIB=.../libopenbpu_noc_wrapper.a`
     - `OPENBPU_NOC_VERILATOR_MODEL_LIB=.../Vnoc_top__ALL.a`

Both the legacy GPGPU-Sim `Makefile` and the CMake build now have link hooks
for these two external archives.

## Current blocker

The remaining integration blocker is operational rather than architectural:

- Remote Ubuntu builds require regenerating Verilator outputs on the remote host
  to avoid host-version mismatches between generated headers and the local
  Verilator runtime
- If the project is copied by `rsync` instead of `git clone`, `setup_environment`
  may emit non-fatal git-metadata errors because `gpgpu-sim/.git` is absent

## Next research step

For a fully faithful dual-network hardware backend, add one of:

- a reverse-direction OpenBPU RTL build for replies, or
- a bidirectional top-level NoC that exposes both request and reply ports
