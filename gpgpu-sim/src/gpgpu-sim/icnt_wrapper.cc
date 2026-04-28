// Copyright (c) 2009-2011, Tor M. Aamodt, Wilson W.L. Fung, Ali Bakhoda
// The University of British Columbia
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
// Redistributions of source code must retain the above copyright notice, this
// list of conditions and the following disclaimer.
// Redistributions in binary form must reproduce the above copyright notice,
// this list of conditions and the following disclaimer in the documentation
// and/or other materials provided with the distribution. Neither the name of
// The University of British Columbia nor the names of its contributors may be
// used to endorse or promote products derived from this software without
// specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
// ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
// LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
// CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
// SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
// INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
// CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
// ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
// POSSIBILITY OF SUCH DAMAGE.

#include "icnt_wrapper.h"

#include <assert.h>

#include <deque>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "../../../interconnect-wrapper/gpgpu_sim_noc_adapter.h"
#include "../../../verilator-noc-lib/noc_verilator_wrapper.h"
#include "../intersim2/globals.hpp"
#include "../intersim2/interconnect_interface.hpp"
#include "../option_parser.h"
#include "local_interconnect.h"
#include "mem_fetch.h"

icnt_create_p icnt_create;
icnt_init_p icnt_init;
icnt_has_buffer_p icnt_has_buffer;
icnt_push_p icnt_push;
icnt_pop_p icnt_pop;
icnt_transfer_p icnt_transfer;
icnt_busy_p icnt_busy;
icnt_display_stats_p icnt_display_stats;
icnt_display_overall_stats_p icnt_display_overall_stats;
icnt_display_state_p icnt_display_state;
icnt_get_flit_size_p icnt_get_flit_size;

unsigned g_network_mode;
char* g_network_config_filename;

struct inct_config g_inct_config;
LocalInterconnect* g_localicnt_interface;

namespace {

struct openbpu_icnt_config {
  unsigned req_rtl_inputs;
  unsigned req_rtl_outputs;
  unsigned packet_bits;
  unsigned flit_data_bits;
  unsigned dest_bits;
  unsigned vc_bits;
  unsigned credit_width;
  unsigned request_pending_per_input;
  unsigned request_pending_per_output;
  unsigned reset_cycles;
  unsigned request_fixed_hops;
  unsigned reply_fallback_latency;
  unsigned reply_fallback_hops;
  unsigned reply_pending_per_input;
  unsigned reply_pending_per_output;
  unsigned flit_size;
};

openbpu_icnt_config g_openbpu_config = {
    80, 64, 64, 53, 7, 1, 5, 256, 4096, 5, 2, 20, 2, 256, 4096, 40};

class FixedLatencyNoc : public openbpu::NocIf {
 public:
  FixedLatencyNoc(const std::string& name, uint32_t num_inputs,
                  uint32_t num_outputs, uint32_t latency_cycles,
                  uint32_t hop_count, uint32_t max_pending_per_input,
                  uint32_t max_pending_per_output)
      : NocIf(name),
        num_inputs_(num_inputs),
        num_outputs_(num_outputs),
        latency_cycles_(latency_cycles),
        hop_count_(hop_count),
        source_depth_(num_inputs, 0),
        pending_by_output_(num_outputs),
        max_pending_per_input_(max_pending_per_input),
        max_pending_per_output_(max_pending_per_output) {}

 protected:
  bool CanAccept(uint32_t src, uint32_t dst,
                 const openbpu::NocPacket& packet) const override {
    if (src >= num_inputs_ || dst >= num_outputs_ || packet.size == 0) {
      return false;
    }
    if (source_depth_[src] >= max_pending_per_input_) {
      return false;
    }
    return pending_by_output_[dst].size() < max_pending_per_output_;
  }

  bool PushImpl(const openbpu::NocPacket& packet) override {
    if (!CanAccept(packet.src_id, packet.dst_id, packet)) {
      return false;
    }

    Pending pending;
    pending.packet = packet;
    pending.ready_cycle = current_cycle() + latency_cycles_;
    pending.hop_count = hop_count_;
    pending_by_output_[packet.dst_id].push_back(pending);
    ++source_depth_[packet.src_id];
    return true;
  }

  bool PopImpl(uint32_t node, openbpu::NocPacket* packet,
               uint32_t* hop_count) override {
    if (node >= num_outputs_ || pending_by_output_[node].empty()) {
      return false;
    }

    Pending& pending = pending_by_output_[node].front();
    if (pending.ready_cycle > current_cycle()) {
      return false;
    }

    *packet = pending.packet;
    *hop_count = pending.hop_count;
    pending_by_output_[node].pop_front();
    assert(source_depth_[packet->src_id] > 0);
    --source_depth_[packet->src_id];
    return true;
  }

  void CycleImpl() override {}

 private:
  struct Pending {
    openbpu::NocPacket packet;
    uint64_t ready_cycle;
    uint32_t hop_count;
  };

  uint32_t num_inputs_;
  uint32_t num_outputs_;
  uint32_t latency_cycles_;
  uint32_t hop_count_;
  std::vector<uint32_t> source_depth_;
  std::vector<std::deque<Pending> > pending_by_output_;
  uint32_t max_pending_per_input_;
  uint32_t max_pending_per_output_;
};

std::unique_ptr<openbpu::GpgpuSimNocAdapter> g_openbpu_noc;
unsigned g_openbpu_shader_nodes = 0;
unsigned g_openbpu_memory_nodes = 0;

openbpu::NocPacketType packet_type_from_mem_fetch(const void* data,
                                                  unsigned input) {
  if (data != 0) {
    const mem_fetch* mf = static_cast<const mem_fetch*>(data);
    switch (mf->get_type()) {
      case READ_REQUEST:
        return openbpu::NocPacketType::kRead;
      case WRITE_REQUEST:
        return openbpu::NocPacketType::kWrite;
      case READ_REPLY:
      case WRITE_ACK:
        return openbpu::NocPacketType::kReply;
    }
  }

  if (input >= g_openbpu_shader_nodes) {
    return openbpu::NocPacketType::kReply;
  }
  return openbpu::NocPacketType::kRead;
}

void print_openbpu_network_stats(const char* prefix,
                                 const openbpu::NocStatsSnapshot& stats) {
  printf("%s_packets_injected = %llu\n", prefix,
         (unsigned long long)stats.packets_injected);
  printf("%s_packets_delivered = %llu\n", prefix,
         (unsigned long long)stats.packets_delivered);
  printf("%s_avg_latency_cycles = %0.4f\n", prefix, stats.avg_latency);
  printf("%s_avg_hops = %0.4f\n", prefix, stats.avg_hops);
  printf("%s_throughput_packets_per_cycle = %0.4f\n", prefix,
         stats.packets_per_cycle);
  printf("%s_throughput_bytes_per_cycle = %0.4f\n", prefix,
         stats.bytes_per_cycle);
}

// Wrapper to intersim2 to accompany old icnt_wrapper.
static void intersim2_create(unsigned int n_shader, unsigned int n_mem) {
  g_icnt_interface->CreateInterconnect(n_shader, n_mem);
}

static void intersim2_init() { g_icnt_interface->Init(); }

static bool intersim2_has_buffer(unsigned input, unsigned int size) {
  return g_icnt_interface->HasBuffer(input, size);
}

static void intersim2_push(unsigned input, unsigned output, void* data,
                           unsigned int size) {
  g_icnt_interface->Push(input, output, data, size);
}

static void* intersim2_pop(unsigned output) {
  return g_icnt_interface->Pop(output);
}

static void intersim2_transfer() { g_icnt_interface->Advance(); }

static bool intersim2_busy() { return g_icnt_interface->Busy(); }

static void intersim2_display_stats() { g_icnt_interface->DisplayStats(); }

static void intersim2_display_overall_stats() {
  g_icnt_interface->DisplayOverallStats();
}

static void intersim2_display_state(FILE* fp) {
  g_icnt_interface->DisplayState(fp);
}

static unsigned intersim2_get_flit_size() {
  return g_icnt_interface->GetFlitSize();
}

//////////////////////////////////////////////////////

static void LocalInterconnect_create(unsigned int n_shader,
                                     unsigned int n_mem) {
  g_localicnt_interface->CreateInterconnect(n_shader, n_mem);
}

static void LocalInterconnect_init() { g_localicnt_interface->Init(); }

static bool LocalInterconnect_has_buffer(unsigned input, unsigned int size) {
  return g_localicnt_interface->HasBuffer(input, size);
}

static void LocalInterconnect_push(unsigned input, unsigned output, void* data,
                                   unsigned int size) {
  g_localicnt_interface->Push(input, output, data, size);
}

static void* LocalInterconnect_pop(unsigned output) {
  return g_localicnt_interface->Pop(output);
}

static void LocalInterconnect_transfer() { g_localicnt_interface->Advance(); }

static bool LocalInterconnect_busy() { return g_localicnt_interface->Busy(); }

static void LocalInterconnect_display_stats() {
  g_localicnt_interface->DisplayStats();
}

static void LocalInterconnect_display_overall_stats() {
  g_localicnt_interface->DisplayOverallStats();
}

static void LocalInterconnect_display_state(FILE* fp) {
  g_localicnt_interface->DisplayState(fp);
}

static unsigned LocalInterconnect_get_flit_size() {
  return g_localicnt_interface->GetFlitSize();
}

//////////////////////////////////////////////////////

static void openbpu_verilator_create(unsigned int n_shader,
                                     unsigned int n_mem) {
  if (n_shader > g_openbpu_config.req_rtl_inputs) {
    throw std::runtime_error(
        "GPGPU-Sim shader cluster count exceeds OpenBPU RTL input ports");
  }
  if (n_mem > g_openbpu_config.req_rtl_outputs) {
    throw std::runtime_error(
        "GPGPU-Sim memory sub-partition count exceeds OpenBPU RTL output ports");
  }

  openbpu::NocVerilatorWrapper::Config request_config;
  request_config.num_input_nodes = g_openbpu_config.req_rtl_inputs;
  request_config.num_output_nodes = g_openbpu_config.req_rtl_outputs;
  request_config.packet_bits = g_openbpu_config.packet_bits;
  request_config.flit_data_bits = g_openbpu_config.flit_data_bits;
  request_config.dest_bits = g_openbpu_config.dest_bits;
  request_config.vc_bits = g_openbpu_config.vc_bits;
  request_config.credit_width = g_openbpu_config.credit_width;
  request_config.max_pending_per_input =
      g_openbpu_config.request_pending_per_input;
  request_config.max_pending_per_output =
      g_openbpu_config.request_pending_per_output;
  request_config.reset_cycles = g_openbpu_config.reset_cycles;
  request_config.fixed_hops = g_openbpu_config.request_fixed_hops;

  std::unique_ptr<openbpu::NocIf> request_noc(
      new openbpu::NocVerilatorWrapper(request_config));
  std::unique_ptr<openbpu::NocIf> reply_noc(new FixedLatencyNoc(
      "openbpu-reply-fallback", n_mem, n_shader,
      g_openbpu_config.reply_fallback_latency,
      g_openbpu_config.reply_fallback_hops,
      g_openbpu_config.reply_pending_per_input,
      g_openbpu_config.reply_pending_per_output));

  g_openbpu_shader_nodes = n_shader;
  g_openbpu_memory_nodes = n_mem;
  g_openbpu_noc.reset(new openbpu::GpgpuSimNocAdapter(
      std::move(request_noc), std::move(reply_noc), n_shader, n_mem));
}

static void openbpu_verilator_init() {}

static bool openbpu_verilator_has_buffer(unsigned input, unsigned int size) {
  assert(g_openbpu_noc.get() != 0);
  openbpu::GpgpuSimNocAdapter::PacketHandle handle;
  handle.size = size;
  handle.type = input >= g_openbpu_shader_nodes ? openbpu::NocPacketType::kReply
                                                : openbpu::NocPacketType::kRead;
  return g_openbpu_noc->HasBuffer(input, handle);
}

static void openbpu_verilator_push(unsigned input, unsigned output, void* data,
                                   unsigned int size) {
  assert(g_openbpu_noc.get() != 0);
  openbpu::GpgpuSimNocAdapter::PacketHandle handle;
  handle.payload = data;
  handle.size = size;
  handle.type = packet_type_from_mem_fetch(data, input);
  if (!g_openbpu_noc->Push(input, output, handle)) {
    throw std::runtime_error("OpenBPU NoC push failed after buffer admission");
  }
}

static void* openbpu_verilator_pop(unsigned output) {
  assert(g_openbpu_noc.get() != 0);
  openbpu::GpgpuSimNocAdapter::PacketHandle handle;
  openbpu::NocDeliveredPacket delivered;
  if (!g_openbpu_noc->Pop(output, &handle, &delivered)) {
    return 0;
  }
  if (handle.payload != 0 && output >= g_openbpu_shader_nodes) {
    mem_fetch* mf = static_cast<mem_fetch*>(handle.payload);
    const unsigned global_spid = output - g_openbpu_shader_nodes;
    const unsigned mf_spid = mf->get_sub_partition_id();
    const unsigned mf_chip = mf->get_tlx_addr().chip;
    if (global_spid != mf_spid) {
      fprintf(stderr,
              "[openbpu][pop-mismatch] output_mem=%u mf_spid=%u mf_chip=%u "
              "packet_id=%llu delivered_dst=%u hops=%u latency=%u\n",
              global_spid, mf_spid, mf_chip,
              (unsigned long long)delivered.packet.packet_id,
              delivered.packet.dst_id, delivered.hop_count,
              delivered.latency_cycles);
    }
  }
  return handle.payload;
}

static void openbpu_verilator_transfer() {
  assert(g_openbpu_noc.get() != 0);
  g_openbpu_noc->Cycle();
}

static bool openbpu_verilator_busy() {
  return g_openbpu_noc.get() != 0 && g_openbpu_noc->Busy();
}

static void openbpu_verilator_display_stats() {
  assert(g_openbpu_noc.get() != 0);
  const openbpu::NocStatsSnapshot request = g_openbpu_noc->RequestStats();
  const openbpu::NocStatsSnapshot reply = g_openbpu_noc->ReplyStats();

  print_openbpu_network_stats("Req_Network_", request);
  printf("\n");
  print_openbpu_network_stats("Reply_Network_", reply);
  printf("\n");
  g_openbpu_noc->PrintStats(std::cout);
}

static void openbpu_verilator_display_overall_stats() {
  openbpu_verilator_display_stats();
}

static void openbpu_verilator_display_state(FILE* fp) {
  const openbpu::NocStatsSnapshot request =
      g_openbpu_noc.get() != 0 ? g_openbpu_noc->RequestStats()
                               : openbpu::NocStatsSnapshot();
  const openbpu::NocStatsSnapshot reply =
      g_openbpu_noc.get() != 0 ? g_openbpu_noc->ReplyStats()
                               : openbpu::NocStatsSnapshot();
  fprintf(fp,
          "OpenBPU NoC backend: shaders=%u, memory_nodes=%u, busy=%s, "
          "req_inflight=%llu, req_delivered=%llu, reply_inflight=%llu, "
          "reply_delivered=%llu, adapter_outstanding=%llu, "
          "adapter_reordered=%llu\n",
          g_openbpu_shader_nodes, g_openbpu_memory_nodes,
          openbpu_verilator_busy() ? "yes" : "no",
          (unsigned long long)request.inflight_packets,
          (unsigned long long)request.packets_delivered,
          (unsigned long long)reply.inflight_packets,
          (unsigned long long)reply.packets_delivered,
          (unsigned long long)(g_openbpu_noc.get() != 0
                                   ? g_openbpu_noc->OutstandingPayloadCount()
                                   : 0),
          (unsigned long long)(g_openbpu_noc.get() != 0
                                   ? g_openbpu_noc->ReorderedRequestCount()
                                   : 0));
}

static unsigned openbpu_verilator_get_flit_size() {
  return g_openbpu_config.flit_size;
}

}  // namespace

void icnt_reg_options(class OptionParser* opp) {
  option_parser_register(opp, "-network_mode", OPT_INT32, &g_network_mode,
                         "Interconnection network mode", "1");
  option_parser_register(opp, "-inter_config_file", OPT_CSTR,
                         &g_network_config_filename,
                         "Interconnection network config file", "mesh");

  // Parameters for local xbar.
  option_parser_register(opp, "-icnt_in_buffer_limit", OPT_UINT32,
                         &g_inct_config.in_buffer_limit, "in_buffer_limit",
                         "64");
  option_parser_register(opp, "-icnt_out_buffer_limit", OPT_UINT32,
                         &g_inct_config.out_buffer_limit, "out_buffer_limit",
                         "64");
  option_parser_register(opp, "-icnt_subnets", OPT_UINT32,
                         &g_inct_config.subnets, "subnets", "2");
  option_parser_register(opp, "-icnt_arbiter_algo", OPT_UINT32,
                         &g_inct_config.arbiter_algo, "arbiter_algo", "1");
  option_parser_register(opp, "-icnt_verbose", OPT_UINT32,
                         &g_inct_config.verbose, "inct_verbose", "0");
  option_parser_register(opp, "-icnt_grant_cycles", OPT_UINT32,
                         &g_inct_config.grant_cycles, "grant_cycles", "1");

  // Parameters for the OpenBPU request-path Verilator backend.
  option_parser_register(opp, "-openbpu_req_rtl_inputs", OPT_UINT32,
                         &g_openbpu_config.req_rtl_inputs,
                         "OpenBPU request-network RTL input port count", "80");
  option_parser_register(opp, "-openbpu_req_rtl_outputs", OPT_UINT32,
                         &g_openbpu_config.req_rtl_outputs,
                         "OpenBPU request-network RTL output port count", "64");
  option_parser_register(opp, "-openbpu_packet_bits", OPT_UINT32,
                         &g_openbpu_config.packet_bits,
                         "Packed OpenBPU flit width exposed to Verilator", "64");
  option_parser_register(opp, "-openbpu_flit_data_bits", OPT_UINT32,
                         &g_openbpu_config.flit_data_bits,
                         "OpenBPU flit payload bit count", "53");
  option_parser_register(opp, "-openbpu_dest_bits", OPT_UINT32,
                         &g_openbpu_config.dest_bits,
                         "OpenBPU destination field bit count", "7");
  option_parser_register(opp, "-openbpu_vc_bits", OPT_UINT32,
                         &g_openbpu_config.vc_bits,
                         "OpenBPU virtual-channel field bit count", "1");
  option_parser_register(opp, "-openbpu_credit_width", OPT_UINT32,
                         &g_openbpu_config.credit_width,
                         "OpenBPU credit signal width", "5");
  option_parser_register(opp, "-openbpu_req_pending_per_input", OPT_UINT32,
                         &g_openbpu_config.request_pending_per_input,
                         "OpenBPU request-network software-side input queue depth",
                         "8");
  option_parser_register(opp, "-openbpu_req_pending_per_output", OPT_UINT32,
                         &g_openbpu_config.request_pending_per_output,
                         "OpenBPU request-network software-side output queue depth",
                         "64");
  option_parser_register(opp, "-openbpu_reset_cycles", OPT_UINT32,
                         &g_openbpu_config.reset_cycles,
                         "Cycles to hold the Verilated OpenBPU model in reset",
                         "5");
  option_parser_register(opp, "-openbpu_req_fixed_hops", OPT_UINT32,
                         &g_openbpu_config.request_fixed_hops,
                         "Fixed hop count recorded for OpenBPU request packets",
                         "2");
  option_parser_register(opp, "-openbpu_reply_latency", OPT_UINT32,
                         &g_openbpu_config.reply_fallback_latency,
                         "Fallback reply-network latency in cycles", "20");
  option_parser_register(opp, "-openbpu_reply_hops", OPT_UINT32,
                         &g_openbpu_config.reply_fallback_hops,
                         "Fallback reply-network hop count", "2");
  option_parser_register(opp, "-openbpu_reply_pending_per_input", OPT_UINT32,
                         &g_openbpu_config.reply_pending_per_input,
                         "Fallback reply-network per-input queue depth", "8");
  option_parser_register(opp, "-openbpu_reply_pending_per_output", OPT_UINT32,
                         &g_openbpu_config.reply_pending_per_output,
                         "Fallback reply-network per-output queue depth", "64");
  option_parser_register(opp, "-openbpu_flit_size", OPT_UINT32,
                         &g_openbpu_config.flit_size,
                         "OpenBPU backend flit size in bytes", "40");
}

void icnt_wrapper_init() {
  switch (g_network_mode) {
    case INTERSIM:
      g_icnt_interface = InterconnectInterface::New(g_network_config_filename);
      icnt_create = intersim2_create;
      icnt_init = intersim2_init;
      icnt_has_buffer = intersim2_has_buffer;
      icnt_push = intersim2_push;
      icnt_pop = intersim2_pop;
      icnt_transfer = intersim2_transfer;
      icnt_busy = intersim2_busy;
      icnt_display_stats = intersim2_display_stats;
      icnt_display_overall_stats = intersim2_display_overall_stats;
      icnt_display_state = intersim2_display_state;
      icnt_get_flit_size = intersim2_get_flit_size;
      break;
    case LOCAL_XBAR:
      g_localicnt_interface = LocalInterconnect::New(g_inct_config);
      icnt_create = LocalInterconnect_create;
      icnt_init = LocalInterconnect_init;
      icnt_has_buffer = LocalInterconnect_has_buffer;
      icnt_push = LocalInterconnect_push;
      icnt_pop = LocalInterconnect_pop;
      icnt_transfer = LocalInterconnect_transfer;
      icnt_busy = LocalInterconnect_busy;
      icnt_display_stats = LocalInterconnect_display_stats;
      icnt_display_overall_stats = LocalInterconnect_display_overall_stats;
      icnt_display_state = LocalInterconnect_display_state;
      icnt_get_flit_size = LocalInterconnect_get_flit_size;
      break;
    case OPENBPU_VERILATOR:
      icnt_create = openbpu_verilator_create;
      icnt_init = openbpu_verilator_init;
      icnt_has_buffer = openbpu_verilator_has_buffer;
      icnt_push = openbpu_verilator_push;
      icnt_pop = openbpu_verilator_pop;
      icnt_transfer = openbpu_verilator_transfer;
      icnt_busy = openbpu_verilator_busy;
      icnt_display_stats = openbpu_verilator_display_stats;
      icnt_display_overall_stats = openbpu_verilator_display_overall_stats;
      icnt_display_state = openbpu_verilator_display_state;
      icnt_get_flit_size = openbpu_verilator_get_flit_size;
      break;
    default:
      assert(0);
      break;
  }
}
