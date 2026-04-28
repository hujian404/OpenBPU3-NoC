#ifndef OPENBPU_INTERCONNECT_WRAPPER_NOC_IF_H_
#define OPENBPU_INTERCONNECT_WRAPPER_NOC_IF_H_

#include <stdint.h>

#include <iosfwd>
#include <map>
#include <string>

namespace openbpu {

enum class NocPacketType {
  kRead = 0,
  kWrite = 1,
  kReply = 2,
};

struct NocPacket {
  uint64_t packet_id;
  uint32_t src_id;
  uint32_t dst_id;
  uint32_t size;
  NocPacketType type;
  uint64_t opaque;

  NocPacket();
};

struct NocDeliveredPacket {
  NocPacket packet;
  uint64_t inject_cycle;
  uint64_t receive_cycle;
  uint64_t latency_cycles;
  uint32_t hop_count;

  NocDeliveredPacket();
};

struct NocStatsSnapshot {
  uint64_t current_cycle;
  uint64_t packets_injected;
  uint64_t packets_delivered;
  uint64_t inflight_packets;
  uint64_t bytes_injected;
  uint64_t bytes_delivered;
  uint64_t total_latency_cycles;
  uint64_t total_hops;
  uint64_t stalled_pushes;
  double avg_latency;
  double avg_hops;
  double packets_per_cycle;
  double bytes_per_cycle;

  NocStatsSnapshot();
};

class NocIf {
 public:
  virtual ~NocIf();

  bool can_push(uint32_t src, uint32_t dst, const NocPacket& packet) const;
  bool push(uint32_t src, uint32_t dst, const NocPacket& packet);
  bool pop(uint32_t node, NocDeliveredPacket* delivered);
  void cycle();

  uint64_t current_cycle() const;
  NocStatsSnapshot stats() const;
  void print_stats(std::ostream& os) const;
  const std::string& name() const;

 protected:
  explicit NocIf(const std::string& name);

  virtual bool CanAccept(uint32_t src, uint32_t dst,
                         const NocPacket& packet) const = 0;
  virtual bool PushImpl(const NocPacket& packet) = 0;
  virtual bool PopImpl(uint32_t node, NocPacket* packet,
                       uint32_t* hop_count) = 0;
  virtual void CycleImpl() = 0;

 private:
  struct InjectionRecord {
    uint64_t inject_cycle;
    NocPacket packet;
  };

  std::string name_;
  uint64_t current_cycle_;
  uint64_t next_packet_id_;
  uint64_t packets_injected_;
  uint64_t packets_delivered_;
  uint64_t bytes_injected_;
  uint64_t bytes_delivered_;
  uint64_t total_latency_cycles_;
  uint64_t total_hops_;
  uint64_t stalled_pushes_;
  std::map<uint64_t, InjectionRecord> inflight_;
};

const char* ToString(NocPacketType type);

}  // namespace openbpu

#endif  // OPENBPU_INTERCONNECT_WRAPPER_NOC_IF_H_
