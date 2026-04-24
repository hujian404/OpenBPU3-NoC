#include "noc_verilator_wrapper.h"

#include <verilated.h>

#include <cstring>
#include <stdexcept>

#if __has_include("Vnoc_top.h")
#include "Vnoc_top.h"
#else
#error "Vnoc_top.h not found. Run scripts/build_noc.sh before compiling the wrapper."
#endif

double sc_time_stamp() { return 0.0; }

namespace openbpu {

namespace {

enum FlitEncoding {
  kFlitData = 0,
  kFlitRequest = 1,
  kFlitResponse = 2,
};

template <typename T>
T* AsWords(void* base) {
  return reinterpret_cast<T*>(base);
}

template <typename T>
const T* AsWords(const void* base) {
  return reinterpret_cast<const T*>(base);
}

uint32_t WordsForBits(uint32_t bits) {
  return (bits + 31u) / 32u;
}

}  // namespace

NocVerilatorWrapper::Config::Config()
    : num_input_nodes(0),
      num_output_nodes(0),
      packet_bits(64),
      flit_data_bits(53),
      dest_bits(7),
      vc_bits(1),
      credit_width(5),
      max_pending_per_input(8),
      max_pending_per_output(64),
      reset_cycles(5),
      fixed_hops(2) {}

NocVerilatorWrapper::NocVerilatorWrapper(const Config& config)
    : NocIf("openbpu-verilator"),
      config_(config),
      context_(0),
      top_(0),
      ingress_queues_(config.num_input_nodes),
      egress_queues_(config.num_output_nodes),
      accepted_inputs_(config.num_input_nodes, false) {
  if (config_.packet_bits > 64) {
    throw std::invalid_argument(
        "Current wrapper encodes a single packet into one 64-bit flit");
  }
  if (config_.flit_data_bits < 32) {
    throw std::invalid_argument("flit_data_bits must be at least 32");
  }
  InitializeModel();
  ResetModel();
}

NocVerilatorWrapper::~NocVerilatorWrapper() {
  delete top_;
  delete context_;
}

bool NocVerilatorWrapper::CanAccept(uint32_t src, uint32_t dst,
                                    const NocPacket& packet) const {
  if (src >= config_.num_input_nodes) {
    return false;
  }
  if (dst >= config_.num_output_nodes) {
    return false;
  }
  if (packet.size == 0) {
    return false;
  }
  return ingress_queues_[src].size() < config_.max_pending_per_input;
}

bool NocVerilatorWrapper::PushImpl(const NocPacket& packet) {
  const uint32_t src = packet.src_id;
  if (src >= config_.num_input_nodes) {
    return false;
  }
  ingress_queues_[src].push_back(BuildEncodedPacket(packet));
  return true;
}

bool NocVerilatorWrapper::PopImpl(uint32_t node, NocPacket* packet,
                                  uint32_t* hop_count) {
  if (packet == 0 || hop_count == 0) {
    throw std::invalid_argument("PopImpl requires valid output pointers");
  }
  if (node >= config_.num_output_nodes) {
    return false;
  }
  if (egress_queues_[node].empty()) {
    return false;
  }

  EgressPacket ready = egress_queues_[node].front();
  egress_queues_[node].pop_front();
  *packet = ready.packet;
  *hop_count = ready.hop_count;
  return true;
}

void NocVerilatorWrapper::CycleImpl() {
  DriveInputs();
  StepClock();
  ConsumeAcceptedInputs();
  CaptureOutputs();
}

void NocVerilatorWrapper::InitializeModel() {
  context_ = new VerilatedContext();
  context_->debug(0);
  top_ = new Vnoc_top(context_);
}

void NocVerilatorWrapper::ResetModel() {
  top_->clk = 0;
  top_->reset = 1;
  for (uint32_t i = 0; i < config_.reset_cycles; ++i) {
    top_->eval();
    top_->clk = !top_->clk;
    top_->eval();
    top_->clk = !top_->clk;
  }
  top_->reset = 0;
  top_->eval();
}

void NocVerilatorWrapper::DriveInputs() {
  const uint32_t packet_words =
      WordsForBits(config_.packet_bits * config_.num_input_nodes);
  const uint32_t valid_words = WordsForBits(config_.num_input_nodes);
  const uint32_t ready_words = WordsForBits(config_.num_output_nodes);

  std::memset(&top_->in_packet, 0, packet_words * sizeof(uint32_t));
  std::memset(&top_->in_valid, 0, valid_words * sizeof(uint32_t));
  std::memset(&top_->out_ready, 0xff, ready_words * sizeof(uint32_t));

  for (uint32_t node = 0; node < config_.num_input_nodes; ++node) {
    accepted_inputs_[node] = false;
    if (ingress_queues_[node].empty()) {
      continue;
    }
    const EncodedPacket& encoded = ingress_queues_[node].front();
    SetFlatBit(&top_->in_valid, node, true);
    SetFlatField(&top_->in_packet, node * config_.packet_bits,
                 config_.packet_bits, EncodeFlitBits(encoded));
  }
}

void NocVerilatorWrapper::StepClock() {
  top_->clk = 0;
  top_->eval();

  for (uint32_t node = 0; node < config_.num_input_nodes; ++node) {
    accepted_inputs_[node] =
        !ingress_queues_[node].empty() && GetFlatBit(&top_->in_ready, node);
  }

  top_->clk = 1;
  top_->eval();
}

void NocVerilatorWrapper::ConsumeAcceptedInputs() {
  for (uint32_t node = 0; node < config_.num_input_nodes; ++node) {
    if (accepted_inputs_[node] && !ingress_queues_[node].empty()) {
      ingress_queues_[node].pop_front();
    }
  }
}

void NocVerilatorWrapper::CaptureOutputs() {
  for (uint32_t node = 0; node < config_.num_output_nodes; ++node) {
    if (!GetFlatBit(&top_->out_valid, node)) {
      continue;
    }
    if (egress_queues_[node].size() >= config_.max_pending_per_output) {
      continue;
    }

    const uint64_t flit_bits =
        GetFlatField(&top_->out_packet, node * config_.packet_bits,
                     config_.packet_bits);
    const uint64_t data_bits =
        flit_bits >> (2u + 1u + config_.vc_bits + config_.dest_bits);
    const uint32_t tracking_id = DecodeTrackingId(data_bits);

    EgressPacket egress;
    egress.packet.packet_id = tracking_id;
    egress.packet.dst_id = node;
    egress.hop_count = config_.fixed_hops;
    egress_queues_[node].push_back(egress);
  }
}

uint64_t NocVerilatorWrapper::EncodeFlitBits(
    const EncodedPacket& packet) const {
  const uint32_t header_shift = 0;
  const uint32_t is_last_shift = header_shift + 2u;
  const uint32_t vc_shift = is_last_shift + 1u;
  const uint32_t dest_shift = vc_shift + config_.vc_bits;
  const uint32_t data_shift = dest_shift + config_.dest_bits;

  uint64_t flit = 0;
  flit |= static_cast<uint64_t>(packet.encoded_type);
  flit |= (static_cast<uint64_t>(1) << is_last_shift);
  flit |= (static_cast<uint64_t>(packet.encoded_vc) << vc_shift);
  flit |= (static_cast<uint64_t>(packet.route_dest) << dest_shift);
  flit |= (packet.tracking_id << data_shift);
  return flit;
}

NocVerilatorWrapper::EncodedPacket NocVerilatorWrapper::BuildEncodedPacket(
    const NocPacket& packet) const {
  EncodedPacket encoded;
  encoded.packet = packet;
  encoded.route_dest = packet.dst_id;
  encoded.encoded_vc = 0;
  encoded.tracking_id = packet.packet_id & ((1ull << config_.flit_data_bits) - 1);

  switch (packet.type) {
    case NocPacketType::kRead:
    case NocPacketType::kWrite:
      encoded.encoded_type = kFlitRequest;
      break;
    case NocPacketType::kReply:
      encoded.encoded_type = kFlitResponse;
      break;
  }
  return encoded;
}

uint32_t NocVerilatorWrapper::DecodeTrackingId(uint64_t data_bits) const {
  return static_cast<uint32_t>(data_bits & 0xffffffffu);
}

void NocVerilatorWrapper::SetFlatBit(void* base, uint32_t bit_index,
                                     bool value) const {
  uint32_t* words = AsWords<uint32_t>(base);
  const uint32_t word_index = bit_index / 32u;
  const uint32_t bit_offset = bit_index % 32u;
  const uint32_t mask = 1u << bit_offset;
  if (value) {
    words[word_index] |= mask;
  } else {
    words[word_index] &= ~mask;
  }
}

bool NocVerilatorWrapper::GetFlatBit(const void* base, uint32_t bit_index) const {
  const uint32_t* words = AsWords<uint32_t>(base);
  const uint32_t word_index = bit_index / 32u;
  const uint32_t bit_offset = bit_index % 32u;
  return ((words[word_index] >> bit_offset) & 1u) != 0u;
}

void NocVerilatorWrapper::SetFlatField(void* base, uint32_t start_bit,
                                       uint32_t width, uint64_t value) const {
  for (uint32_t i = 0; i < width; ++i) {
    SetFlatBit(base, start_bit + i, ((value >> i) & 1ull) != 0ull);
  }
}

uint64_t NocVerilatorWrapper::GetFlatField(const void* base, uint32_t start_bit,
                                           uint32_t width) const {
  uint64_t value = 0;
  for (uint32_t i = 0; i < width && i < 64; ++i) {
    if (GetFlatBit(base, start_bit + i)) {
      value |= (1ull << i);
    }
  }
  return value;
}

}  // namespace openbpu
