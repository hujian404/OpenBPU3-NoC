#include "noc_if.h"

#include <iomanip>
#include <ostream>
#include <stdexcept>

namespace openbpu {

namespace {

double SafeDivide(uint64_t numerator, uint64_t denominator) {
  if (denominator == 0) {
    return 0.0;
  }
  return static_cast<double>(numerator) / static_cast<double>(denominator);
}

}  // namespace

NocPacket::NocPacket()
    : packet_id(0), src_id(0), dst_id(0), size(0), type(NocPacketType::kRead),
      opaque(0) {}

NocDeliveredPacket::NocDeliveredPacket()
    : inject_cycle(0), receive_cycle(0), latency_cycles(0), hop_count(0) {}

NocStatsSnapshot::NocStatsSnapshot()
    : current_cycle(0),
      packets_injected(0),
      packets_delivered(0),
      inflight_packets(0),
      bytes_injected(0),
      bytes_delivered(0),
      total_latency_cycles(0),
      total_hops(0),
      stalled_pushes(0),
      avg_latency(0.0),
      avg_hops(0.0),
      packets_per_cycle(0.0),
      bytes_per_cycle(0.0) {}

NocIf::NocIf(const std::string& name)
    : name_(name),
      current_cycle_(0),
      next_packet_id_(1),
      packets_injected_(0),
      packets_delivered_(0),
      bytes_injected_(0),
      bytes_delivered_(0),
      total_latency_cycles_(0),
      total_hops_(0),
      stalled_pushes_(0) {}

NocIf::~NocIf() {}

bool NocIf::can_push(uint32_t src, uint32_t dst, const NocPacket& packet) const {
  NocPacket candidate = packet;
  candidate.src_id = src;
  candidate.dst_id = dst;
  return CanAccept(src, dst, candidate);
}

bool NocIf::push(uint32_t src, uint32_t dst, const NocPacket& packet) {
  NocPacket candidate = packet;
  candidate.src_id = src;
  candidate.dst_id = dst;
  if (candidate.packet_id == 0) {
    candidate.packet_id = next_packet_id_++;
  }

  if (!CanAccept(src, dst, candidate)) {
    ++stalled_pushes_;
    return false;
  }

  if (!PushImpl(candidate)) {
    ++stalled_pushes_;
    return false;
  }

  InjectionRecord record;
  record.inject_cycle = current_cycle_;
  record.packet = candidate;
  inflight_[candidate.packet_id] = record;

  ++packets_injected_;
  bytes_injected_ += candidate.size;
  return true;
}

bool NocIf::pop(uint32_t node, NocDeliveredPacket* delivered) {
  if (delivered == 0) {
    throw std::invalid_argument("NocIf::pop requires a valid output pointer");
  }

  NocPacket packet;
  uint32_t hop_count = 0;
  if (!PopImpl(node, &packet, &hop_count)) {
    return false;
  }

  std::map<uint64_t, InjectionRecord>::iterator it =
      inflight_.find(packet.packet_id);
  if (it == inflight_.end()) {
    throw std::runtime_error("Received packet that is not tracked as in-flight");
  }

  delivered->packet = it->second.packet;
  delivered->receive_cycle = current_cycle_;
  delivered->inject_cycle = it->second.inject_cycle;
  delivered->latency_cycles = current_cycle_ - it->second.inject_cycle;
  delivered->hop_count = hop_count;

  ++packets_delivered_;
  bytes_delivered_ += it->second.packet.size;
  total_latency_cycles_ += delivered->latency_cycles;
  total_hops_ += delivered->hop_count;
  inflight_.erase(it);
  return true;
}

void NocIf::cycle() {
  CycleImpl();
  ++current_cycle_;
}

uint64_t NocIf::current_cycle() const { return current_cycle_; }

NocStatsSnapshot NocIf::stats() const {
  NocStatsSnapshot snapshot;
  snapshot.current_cycle = current_cycle_;
  snapshot.packets_injected = packets_injected_;
  snapshot.packets_delivered = packets_delivered_;
  snapshot.inflight_packets = inflight_.size();
  snapshot.bytes_injected = bytes_injected_;
  snapshot.bytes_delivered = bytes_delivered_;
  snapshot.total_latency_cycles = total_latency_cycles_;
  snapshot.total_hops = total_hops_;
  snapshot.stalled_pushes = stalled_pushes_;
  snapshot.avg_latency = SafeDivide(total_latency_cycles_, packets_delivered_);
  snapshot.avg_hops = SafeDivide(total_hops_, packets_delivered_);
  snapshot.packets_per_cycle = SafeDivide(packets_delivered_, current_cycle_);
  snapshot.bytes_per_cycle = SafeDivide(bytes_delivered_, current_cycle_);
  return snapshot;
}

void NocIf::print_stats(std::ostream& os) const {
  const NocStatsSnapshot snapshot = stats();
  os << "noc_name=" << name_ << '\n';
  os << "current_cycle=" << snapshot.current_cycle << '\n';
  os << "packets_injected=" << snapshot.packets_injected << '\n';
  os << "packets_delivered=" << snapshot.packets_delivered << '\n';
  os << "inflight_packets=" << snapshot.inflight_packets << '\n';
  os << "bytes_injected=" << snapshot.bytes_injected << '\n';
  os << "bytes_delivered=" << snapshot.bytes_delivered << '\n';
  os << "stalled_pushes=" << snapshot.stalled_pushes << '\n';
  os << std::fixed << std::setprecision(3);
  os << "avg_latency_cycles=" << snapshot.avg_latency << '\n';
  os << "avg_hops=" << snapshot.avg_hops << '\n';
  os << "throughput_packets_per_cycle=" << snapshot.packets_per_cycle << '\n';
  os << "throughput_bytes_per_cycle=" << snapshot.bytes_per_cycle << '\n';
}

const std::string& NocIf::name() const { return name_; }

const char* ToString(NocPacketType type) {
  switch (type) {
    case NocPacketType::kRead:
      return "read";
    case NocPacketType::kWrite:
      return "write";
    case NocPacketType::kReply:
      return "reply";
  }
  return "unknown";
}

}  // namespace openbpu
