#ifndef OPENBPU_INTERCONNECT_WRAPPER_GPGPU_SIM_NOC_ADAPTER_H_
#define OPENBPU_INTERCONNECT_WRAPPER_GPGPU_SIM_NOC_ADAPTER_H_

#include "noc_if.h"

#include <stdint.h>

#include <iosfwd>
#include <deque>
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
                     std::unique_ptr<NocIf> reply_noc,
                     uint32_t num_shader_nodes,
                     uint32_t num_memory_nodes);

  bool HasBuffer(uint32_t src, const PacketHandle& handle) const;
  bool Push(uint32_t src, uint32_t dst, const PacketHandle& handle);
  bool Pop(uint32_t node, PacketHandle* handle, NocDeliveredPacket* meta);
  void Cycle();
  bool Busy() const;

  const NocStatsSnapshot RequestStats() const;
  const NocStatsSnapshot ReplyStats() const;
  void PrintStats(std::ostream& os) const;
  uint64_t OutstandingPayloadCount() const;
  uint64_t ReorderedRequestCount() const;
  void DebugState(std::ostream& os) const;

 private:
  struct RoutedEndpoint {
    NocIf* noc;
    uint32_t local_src;
    uint32_t local_dst;
  };

  struct PopEndpoint {
    NocIf* noc;
    uint32_t local_node;
  };

  struct ReorderedDelivery {
    PacketHandle handle;
    NocDeliveredPacket meta;
  };

  RoutedEndpoint RoutePush(uint32_t src, uint32_t dst,
                           NocPacketType type) const;
  PopEndpoint RoutePop(uint32_t node) const;
  bool ServeReordered(uint32_t local_node, PacketHandle* handle,
                      NocDeliveredPacket* meta);
  bool TryPopFromNode(NocIf* noc, uint32_t actual_local_node,
                      uint32_t requested_local_node, PacketHandle* handle,
                      NocDeliveredPacket* meta);

  std::unique_ptr<NocIf> request_noc_;
  std::unique_ptr<NocIf> reply_noc_;
  uint32_t num_shader_nodes_;
  uint32_t num_memory_nodes_;
  uint64_t next_packet_id_;
  std::map<uint64_t, PacketHandle> outstanding_payloads_;
  std::map<uint32_t, std::deque<ReorderedDelivery> > reordered_request_packets_;
};

}  // namespace openbpu

#endif  // OPENBPU_INTERCONNECT_WRAPPER_GPGPU_SIM_NOC_ADAPTER_H_
