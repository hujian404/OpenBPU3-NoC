#ifndef OPENBPU_INTERCONNECT_WRAPPER_GPGPU_SIM_NOC_ADAPTER_H_
#define OPENBPU_INTERCONNECT_WRAPPER_GPGPU_SIM_NOC_ADAPTER_H_

#include "noc_if.h"

#include <stdint.h>

#include <iosfwd>
#include <map>
#include <memory>

namespace openbpu {

class GpgpuSimNocAdapter {
 public:
  struct PacketHandle {
    void* payload;
    uint32_t size;
    NocPacketType type;

    PacketHandle();
  };

  GpgpuSimNocAdapter(std::unique_ptr<NocIf> request_noc,
                     std::unique_ptr<NocIf> reply_noc);

  bool HasBuffer(uint32_t src, uint32_t dst, const PacketHandle& handle) const;
  bool Push(uint32_t src, uint32_t dst, const PacketHandle& handle);
  bool Pop(uint32_t node, PacketHandle* handle, NocDeliveredPacket* meta);
  void Cycle();

  const NocStatsSnapshot RequestStats() const;
  const NocStatsSnapshot ReplyStats() const;
  void PrintStats(std::ostream& os) const;

 private:
  NocIf* SelectNetwork(NocPacketType type) const;

  std::unique_ptr<NocIf> request_noc_;
  std::unique_ptr<NocIf> reply_noc_;
  uint64_t next_packet_id_;
  std::map<uint64_t, PacketHandle> outstanding_payloads_;
};

}  // namespace openbpu

#endif  // OPENBPU_INTERCONNECT_WRAPPER_GPGPU_SIM_NOC_ADAPTER_H_
