#ifndef OPENBPU_VERILATOR_NOC_LIB_NOC_VERILATOR_WRAPPER_H_
#define OPENBPU_VERILATOR_NOC_LIB_NOC_VERILATOR_WRAPPER_H_

#include "../interconnect-wrapper/noc_if.h"

#include <stdint.h>

#include <deque>
#include <set>
#include <string>
#include <vector>

class VerilatedContext;
class Vnoc_top;

namespace openbpu {

class NocVerilatorWrapper : public NocIf {
 public:
  struct Config {
    uint32_t num_input_nodes;
    uint32_t num_output_nodes;
    uint32_t packet_bits;
    uint32_t flit_data_bits;
    uint32_t dest_bits;
    uint32_t vc_bits;
    uint32_t credit_width;
    uint32_t max_pending_per_input;
    uint32_t max_pending_per_output;
    uint32_t reset_cycles;
    uint32_t fixed_hops;
    uint32_t min_input_hold_cycles;
    std::string trace_path;

    Config();
  };

  explicit NocVerilatorWrapper(const Config& config);
  ~NocVerilatorWrapper() override;

 protected:
  bool CanAccept(uint32_t src, uint32_t dst,
                 const NocPacket& packet) const override;
  bool PushImpl(const NocPacket& packet) override;
  bool PopImpl(uint32_t node, NocPacket* packet, uint32_t* hop_count) override;
  void CycleImpl() override;

 private:
  struct EncodedPacket {
    NocPacket packet;
    uint32_t route_dest;
    uint32_t encoded_type;
    uint32_t encoded_vc;
    uint64_t tracking_id;
  };

  struct EgressPacket {
    NocPacket packet;
    uint32_t hop_count;
  };

  void InitializeModel();
  void ResetModel();
  void DriveInputs();
  void StepClock();
  void ConsumeAcceptedInputs();
  void CaptureOutputs();

  uint64_t EncodeFlitBits(const EncodedPacket& packet) const;
  EncodedPacket BuildEncodedPacket(const NocPacket& packet) const;
  uint32_t DecodeTrackingId(uint64_t data_bits) const;

  void SetFlatBit(void* base, uint32_t bit_index, bool value) const;
  bool GetFlatBit(const void* base, uint32_t bit_index) const;
  void SetFlatField(void* base, uint32_t start_bit, uint32_t width,
                    uint64_t value) const;
  uint64_t GetFlatField(const void* base, uint32_t start_bit,
                        uint32_t width) const;

  Config config_;
  VerilatedContext* context_;
  Vnoc_top* top_;
  std::vector<std::deque<EncodedPacket> > ingress_queues_;
  std::vector<std::deque<EgressPacket> > egress_queues_;
  std::vector<bool> accepted_inputs_;
  std::vector<uint32_t> input_presentation_cycles_;
  std::vector<bool> previous_output_valid_;
  std::vector<uint64_t> previous_output_flit_;
  std::set<uint64_t> seen_output_packet_ids_;
  int32_t active_source_node_;
  uint64_t active_tracking_id_;
  bool active_packet_accepted_;
  uint32_t next_source_rr_;
};

}  // namespace openbpu

#endif  // OPENBPU_VERILATOR_NOC_LIB_NOC_VERILATOR_WRAPPER_H_
