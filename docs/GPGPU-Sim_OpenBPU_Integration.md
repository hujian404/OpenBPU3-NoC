# OpenBPU NoC Integration Hook Points

This repository keeps the GPGPU-Sim change surface intentionally small.

## Hook points

Upstream GPGPU-Sim keeps the interconnect boundary behind the classic wrapper API:

- `icnt_has_buffer(...)`
- `icnt_push(...)`
- `icnt_pop(...)`
- `icnt_transfer()`

Based on the upstream GPGPU-Sim manual and source layout, those wrapper functions live around:

- `src/gpgpu-sim/icnt_wrapper.cc`
- `src/gpgpu-sim/icnt_wrapper.h`
- delegated backend in `src/intersim2/interconnect_interface.cpp`

The cleanest integration is to leave the existing call sites in shader cores, memory partitions, and GPU cycle scheduling untouched, and only replace the backend implementation behind `icnt_wrapper`.

## Minimal adapter patch shape

```cpp
// src/gpgpu-sim/icnt_wrapper.cc
#include "gpgpu_sim_noc_adapter.h"

static openbpu::GpgpuSimNocAdapter* g_openbpu_noc = nullptr;

void icnt_init() {
  // Create request network here.
}

unsigned icnt_has_buffer(unsigned input, unsigned output, unsigned size) {
  openbpu::GpgpuSimNocAdapter::PacketHandle handle;
  handle.size = size;
  handle.type = openbpu::NocPacketType::kRead;
  return g_openbpu_noc->HasBuffer(input, output, handle) ? 1 : 0;
}

void icnt_push(unsigned input, unsigned output, void* data, unsigned size) {
  openbpu::GpgpuSimNocAdapter::PacketHandle handle;
  handle.payload = data;
  handle.size = size;
  handle.type = openbpu::NocPacketType::kRead;
  g_openbpu_noc->Push(input, output, handle);
}

void* icnt_pop(unsigned output) {
  openbpu::GpgpuSimNocAdapter::PacketHandle handle;
  openbpu::NocDeliveredPacket meta;
  if (!g_openbpu_noc->Pop(output, &handle, &meta)) return nullptr;
  return handle.payload;
}

void icnt_transfer() {
  g_openbpu_noc->Cycle();
}
```

## Research notes

- `noc_if` is simulator-agnostic and should stay that way.
- `GpgpuSimNocAdapter` is the only layer that should know about `void*` packet payloads.
- For full request/reply timing fidelity, instantiate one request NoC and one reply NoC, matching GPGPU-Sim’s traditional dual-network model.
- The current Chisel top-level is request-direction shaped (`SM -> L2`). To time replies faithfully, generate a second Verilated build with swapped node counts or extend the RTL with a reverse path.
