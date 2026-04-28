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

## Flit-format rationale

The current request-network shim assumes a single-flit packet format derived
directly from `NoCParams`:

- `2` bits: `flitType`
- `1` bit: `isLast`
- `log2Ceil(numVCs)` bits: `vc`
- `log2Ceil(max(numSMs, numL2Slices))` bits: `destId`
- remaining bits: `data`

For the default 80-SM / 64-L2 configuration this resolves to:

- `packet_bits = 64`
- `vc_bits = 1`
- `dest_bits = 7`
- `data_bits = 53`

`scripts/build_noc.sh` now reads these values from
`generated/openbpu_noc_meta.env`, which is emitted by the Chisel generator.
That keeps RTL, shim wiring, and Verilator wrapper defaults aligned.

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
   - reads `generated/openbpu_noc_meta.env`
   - runs Verilator
   - builds `verilator-noc-lib/build/obj_dir/Vnoc_top__ALL.a`
2. `scripts/build_sim.sh`
   - auto-applies `patches/gpgpu-sim-openbpu-integration.patch` when needed
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
- The current Verilator wrapper still serializes request injection aggressively
  to match observed RTL behavior; this preserves liveness better than naive
  multi-source driving, but currently limits throughput substantially
- A standalone probe now shows:
  - single-packet delivery succeeds
  - light round-robin multi-source delivery succeeds
  - hotspot multi-packet traffic still loses forward progress under contention
  - hotspot sweeps indicate the current cliff appears when burst depth grows,
    not from contention alone
  - light hotspot cases can report full delivery only because the wrapper is
    suppressing duplicate output packet IDs
  - wrapper input-hold sweeps show the current behavior is heuristic-sensitive;
    the repository now defaults to `hold=5`, which locally performs slightly
    better than the older `hold=3`, but neither is protocol-clean
- Remote bounded `backprop` validation on the configured Ubuntu server now shows a much
  better but still incomplete request path:
  - `Req_Network__packets_injected = 1462`
  - `Req_Network__packets_delivered = 821`
  - `outstanding_payloads = 642`
  - `hold=3` and `hold=5` produced the same bounded interconnect result
- `scripts/run_noc_probe.sh` was adjusted to avoid unconditionally compiling
  `verilated_threads.cpp`, which kept the standalone probe from building on the
  server's Verilator `4.204`
- The repaired server-side probe now shows:
  - `single` traffic still delivers correctly in `18` cycles
  - `4x4 hotspot` with `hold=3/5` can report `16/16` delivery
  - but that apparent success still includes `27` duplicate-output suppressions
  - therefore probe-level full delivery does not yet imply full-system request
    correctness
- Chisel diagnostics now also show two distinct RTL-facing issues:
  - natural one-cycle hotspot bursts can still lose packets
  - wrapper-compatible held-valid hotspot bursts can surface duplicate packets

## Next research step

For a fully faithful dual-network hardware backend, add one of:

- a reverse-direction OpenBPU RTL build for replies, or
- a bidirectional top-level NoC that exposes both request and reply ports
