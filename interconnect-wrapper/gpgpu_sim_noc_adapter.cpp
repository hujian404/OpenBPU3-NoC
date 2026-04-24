#include "gpgpu_sim_noc_adapter.h"

#include <ostream>
#include <stdexcept>

namespace openbpu {

GpgpuSimNocAdapter::PacketHandle::PacketHandle()
    : payload(0), size(0), type(NocPacketType::kRead) {}

GpgpuSimNocAdapter::GpgpuSimNocAdapter(std::unique_ptr<NocIf> request_noc,
                                       std::unique_ptr<NocIf> reply_noc)
    : request_noc_(request_noc.release()),
      reply_noc_(reply_noc.release()),
      next_packet_id_(1) {
  if (!request_noc_) {
    throw std::invalid_argument("request_noc must not be null");
  }
}

bool GpgpuSimNocAdapter::HasBuffer(uint32_t src, uint32_t dst,
                                   const PacketHandle& handle) const {
  NocIf* noc = SelectNetwork(handle.type);
  if (noc == 0) {
    return false;
  }

  NocPacket packet;
  packet.src_id = src;
  packet.dst_id = dst;
  packet.size = handle.size;
  packet.type = handle.type;
  return noc->can_push(src, dst, packet);
}

bool GpgpuSimNocAdapter::Push(uint32_t src, uint32_t dst,
                              const PacketHandle& handle) {
  NocIf* noc = SelectNetwork(handle.type);
  if (noc == 0) {
    return false;
  }

  NocPacket packet;
  packet.src_id = src;
  packet.dst_id = dst;
  packet.size = handle.size;
  packet.type = handle.type;
  packet.packet_id = next_packet_id_++;
  if (!noc->push(src, dst, packet)) {
    return false;
  }

  outstanding_payloads_[packet.packet_id] = handle;
  return true;
}

bool GpgpuSimNocAdapter::Pop(uint32_t node, PacketHandle* handle,
                             NocDeliveredPacket* meta) {
  if (handle == 0 || meta == 0) {
    throw std::invalid_argument("Pop requires non-null output pointers");
  }

  NocDeliveredPacket delivered;
  if (reply_noc_ && reply_noc_->pop(node, &delivered)) {
    *meta = delivered;
  } else if (request_noc_->pop(node, &delivered)) {
    *meta = delivered;
  } else {
    return false;
  }

  std::map<uint64_t, PacketHandle>::iterator it =
      outstanding_payloads_.find(delivered.packet.packet_id);
  if (it == outstanding_payloads_.end()) {
    throw std::runtime_error("Missing payload handle for delivered packet");
  }

  *handle = it->second;
  outstanding_payloads_.erase(it);
  return true;
}

void GpgpuSimNocAdapter::Cycle() {
  request_noc_->cycle();
  if (reply_noc_) {
    reply_noc_->cycle();
  }
}

const NocStatsSnapshot GpgpuSimNocAdapter::RequestStats() const {
  return request_noc_->stats();
}

const NocStatsSnapshot GpgpuSimNocAdapter::ReplyStats() const {
  if (reply_noc_) {
    return reply_noc_->stats();
  }
  return NocStatsSnapshot();
}

void GpgpuSimNocAdapter::PrintStats(std::ostream& os) const {
  os << "[request_noc]\n";
  request_noc_->print_stats(os);
  if (reply_noc_) {
    os << "[reply_noc]\n";
    reply_noc_->print_stats(os);
  }
}

NocIf* GpgpuSimNocAdapter::SelectNetwork(NocPacketType type) const {
  if (type == NocPacketType::kReply) {
    return reply_noc_.get();
  }
  return request_noc_.get();
}

}  // namespace openbpu
