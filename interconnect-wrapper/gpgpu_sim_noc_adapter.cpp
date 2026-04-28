#include "gpgpu_sim_noc_adapter.h"

#include <ostream>
#include <stdexcept>

namespace openbpu {

GpgpuSimNocAdapter::PacketHandle::PacketHandle()
    : payload(0), size(0), type(NocPacketType::kRead) {}

GpgpuSimNocAdapter::GpgpuSimNocAdapter(std::unique_ptr<NocIf> request_noc,
                                       std::unique_ptr<NocIf> reply_noc,
                                       uint32_t num_shader_nodes,
                                       uint32_t num_memory_nodes)
    : request_noc_(request_noc.release()),
      reply_noc_(reply_noc.release()),
      num_shader_nodes_(num_shader_nodes),
      num_memory_nodes_(num_memory_nodes),
      next_packet_id_(1) {
  if (!request_noc_) {
    throw std::invalid_argument("request_noc must not be null");
  }
}

bool GpgpuSimNocAdapter::HasBuffer(uint32_t src,
                                   const PacketHandle& handle) const {
  const uint32_t dst =
      handle.type == NocPacketType::kReply ? 0 : num_shader_nodes_;
  const RoutedEndpoint routed = RoutePush(src, dst, handle.type);

  NocPacket packet;
  packet.src_id = routed.local_src;
  packet.dst_id = routed.local_dst;
  packet.size = handle.size;
  packet.type = handle.type;
  return routed.noc->can_push(routed.local_src, routed.local_dst, packet);
}

bool GpgpuSimNocAdapter::Push(uint32_t src, uint32_t dst,
                              const PacketHandle& handle) {
  const RoutedEndpoint routed = RoutePush(src, dst, handle.type);

  NocPacket packet;
  packet.src_id = routed.local_src;
  packet.dst_id = routed.local_dst;
  packet.size = handle.size;
  packet.type = handle.type;
  packet.packet_id = next_packet_id_++;
  if (!routed.noc->push(routed.local_src, routed.local_dst, packet)) {
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

  const PopEndpoint routed = RoutePop(node);
  if (routed.noc == request_noc_.get()) {
    if (ServeReordered(routed.local_node, handle, meta)) {
      return true;
    }
    if (TryPopFromNode(routed.noc, routed.local_node, routed.local_node, handle,
                       meta)) {
      return true;
    }
    for (uint32_t actual = 0; actual < num_memory_nodes_; ++actual) {
      if (actual == routed.local_node) {
        continue;
      }
      if (TryPopFromNode(routed.noc, actual, routed.local_node, handle, meta)) {
        return true;
      }
    }
    return false;
  }

  NocDeliveredPacket delivered;
  if (!routed.noc->pop(routed.local_node, &delivered)) {
    return false;
  }
  *meta = delivered;

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

bool GpgpuSimNocAdapter::Busy() const {
  return !outstanding_payloads_.empty() || !reordered_request_packets_.empty();
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
  DebugState(os);
}

uint64_t GpgpuSimNocAdapter::OutstandingPayloadCount() const {
  return outstanding_payloads_.size();
}

uint64_t GpgpuSimNocAdapter::ReorderedRequestCount() const {
  uint64_t total = 0;
  for (std::map<uint32_t, std::deque<ReorderedDelivery> >::const_iterator it =
           reordered_request_packets_.begin();
       it != reordered_request_packets_.end(); ++it) {
    total += it->second.size();
  }
  return total;
}

void GpgpuSimNocAdapter::DebugState(std::ostream& os) const {
  os << "[adapter]\n";
  os << "outstanding_payloads=" << OutstandingPayloadCount() << '\n';
  os << "reordered_request_packets=" << ReorderedRequestCount() << '\n';
  if (!reordered_request_packets_.empty()) {
    os << "reordered_destinations=";
    bool first = true;
    for (std::map<uint32_t, std::deque<ReorderedDelivery> >::const_iterator it =
             reordered_request_packets_.begin();
         it != reordered_request_packets_.end(); ++it) {
      if (!first) {
        os << ',';
      }
      os << it->first << ':' << it->second.size();
      first = false;
    }
    os << '\n';
  }
}

GpgpuSimNocAdapter::RoutedEndpoint GpgpuSimNocAdapter::RoutePush(
    uint32_t src, uint32_t dst, NocPacketType type) const {
  RoutedEndpoint routed;
  routed.noc = 0;
  routed.local_src = 0;
  routed.local_dst = 0;

  if (type == NocPacketType::kReply) {
    if (!reply_noc_) {
      throw std::runtime_error("Reply network is not configured");
    }
    if (src < num_shader_nodes_ || src >= num_shader_nodes_ + num_memory_nodes_) {
      throw std::out_of_range("Reply source node is outside memory-node range");
    }
    if (dst >= num_shader_nodes_) {
      throw std::out_of_range("Reply destination node is outside shader range");
    }
    routed.noc = reply_noc_.get();
    routed.local_src = src - num_shader_nodes_;
    routed.local_dst = dst;
    return routed;
  }

  if (src >= num_shader_nodes_) {
    throw std::out_of_range("Request source node is outside shader range");
  }
  if (dst < num_shader_nodes_ || dst >= num_shader_nodes_ + num_memory_nodes_) {
    throw std::out_of_range("Request destination node is outside memory range");
  }
  routed.noc = request_noc_.get();
  routed.local_src = src;
  routed.local_dst = dst - num_shader_nodes_;
  return routed;
}

GpgpuSimNocAdapter::PopEndpoint GpgpuSimNocAdapter::RoutePop(
    uint32_t node) const {
  PopEndpoint routed;
  routed.noc = 0;
  routed.local_node = 0;

  if (node < num_shader_nodes_) {
    if (!reply_noc_) {
      throw std::runtime_error("Reply network is not configured");
    }
    routed.noc = reply_noc_.get();
    routed.local_node = node;
    return routed;
  }

  if (node >= num_shader_nodes_ + num_memory_nodes_) {
    throw std::out_of_range("Pop node is outside the global interconnect range");
  }
  routed.noc = request_noc_.get();
  routed.local_node = node - num_shader_nodes_;
  return routed;
}

bool GpgpuSimNocAdapter::ServeReordered(uint32_t local_node,
                                        PacketHandle* handle,
                                        NocDeliveredPacket* meta) {
  std::map<uint32_t, std::deque<ReorderedDelivery> >::iterator it =
      reordered_request_packets_.find(local_node);
  if (it == reordered_request_packets_.end() || it->second.empty()) {
    return false;
  }

  ReorderedDelivery ready = it->second.front();
  it->second.pop_front();
  if (it->second.empty()) {
    reordered_request_packets_.erase(it);
  }
  *handle = ready.handle;
  *meta = ready.meta;
  return true;
}

bool GpgpuSimNocAdapter::TryPopFromNode(NocIf* noc, uint32_t actual_local_node,
                                        uint32_t requested_local_node,
                                        PacketHandle* handle,
                                        NocDeliveredPacket* meta) {
  NocDeliveredPacket delivered;
  if (!noc->pop(actual_local_node, &delivered)) {
    return false;
  }

  std::map<uint64_t, PacketHandle>::iterator it =
      outstanding_payloads_.find(delivered.packet.packet_id);
  if (it == outstanding_payloads_.end()) {
    throw std::runtime_error("Missing payload handle for delivered packet");
  }

  PacketHandle resolved = it->second;
  outstanding_payloads_.erase(it);

  if (delivered.packet.dst_id == requested_local_node) {
    *handle = resolved;
    *meta = delivered;
    return true;
  }

  ReorderedDelivery stash;
  stash.handle = resolved;
  stash.meta = delivered;
  reordered_request_packets_[delivered.packet.dst_id].push_back(stash);
  return false;
}

}  // namespace openbpu
