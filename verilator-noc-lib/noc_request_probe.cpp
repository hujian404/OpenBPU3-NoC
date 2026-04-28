#include "noc_verilator_wrapper.h"

#include <stdint.h>

#include <cstdlib>
#include <iostream>
#include <map>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

struct ProbeOptions {
  uint32_t num_inputs = 80;
  uint32_t num_outputs = 64;
  uint32_t packet_bits = 64;
  uint32_t flit_data_bits = 53;
  uint32_t dest_bits = 7;
  uint32_t vc_bits = 1;
  uint32_t credit_width = 5;
  uint32_t packets_per_source = 1;
  uint32_t active_sources = 4;
  uint32_t max_cycles = 5000;
  uint32_t fixed_hops = 2;
  uint32_t min_input_hold_cycles = 5;
  uint32_t packet_size = 8;
  std::string mode = "roundrobin";
};

struct SourceStats {
  uint64_t injected = 0;
  uint64_t delivered = 0;
};

uint32_t ParseUint32(const char* value, const std::string& flag) {
  char* end = 0;
  const unsigned long parsed = std::strtoul(value, &end, 10);
  if (value == end || *end != '\0' || parsed > 0xfffffffful) {
    throw std::invalid_argument("Invalid integer for " + flag);
  }
  return static_cast<uint32_t>(parsed);
}

ProbeOptions ParseArgs(int argc, char** argv) {
  ProbeOptions opts;
  for (int i = 1; i < argc; ++i) {
    const std::string arg(argv[i]);
    if (arg == "--num-inputs" && i + 1 < argc) {
      opts.num_inputs = ParseUint32(argv[++i], arg);
    } else if (arg == "--num-outputs" && i + 1 < argc) {
      opts.num_outputs = ParseUint32(argv[++i], arg);
    } else if (arg == "--packet-bits" && i + 1 < argc) {
      opts.packet_bits = ParseUint32(argv[++i], arg);
    } else if (arg == "--flit-data-bits" && i + 1 < argc) {
      opts.flit_data_bits = ParseUint32(argv[++i], arg);
    } else if (arg == "--dest-bits" && i + 1 < argc) {
      opts.dest_bits = ParseUint32(argv[++i], arg);
    } else if (arg == "--vc-bits" && i + 1 < argc) {
      opts.vc_bits = ParseUint32(argv[++i], arg);
    } else if (arg == "--credit-width" && i + 1 < argc) {
      opts.credit_width = ParseUint32(argv[++i], arg);
    } else if (arg == "--packets-per-source" && i + 1 < argc) {
      opts.packets_per_source = ParseUint32(argv[++i], arg);
    } else if (arg == "--active-sources" && i + 1 < argc) {
      opts.active_sources = ParseUint32(argv[++i], arg);
    } else if (arg == "--max-cycles" && i + 1 < argc) {
      opts.max_cycles = ParseUint32(argv[++i], arg);
    } else if (arg == "--fixed-hops" && i + 1 < argc) {
      opts.fixed_hops = ParseUint32(argv[++i], arg);
    } else if (arg == "--min-input-hold-cycles" && i + 1 < argc) {
      opts.min_input_hold_cycles = ParseUint32(argv[++i], arg);
    } else if (arg == "--packet-size" && i + 1 < argc) {
      opts.packet_size = ParseUint32(argv[++i], arg);
    } else if (arg == "--mode" && i + 1 < argc) {
      opts.mode = argv[++i];
    } else if (arg == "--help") {
      std::cout
          << "Usage: noc_request_probe [options]\n"
          << "  --mode single|hotspot|roundrobin\n"
          << "  --active-sources N\n"
          << "  --packets-per-source N\n"
          << "  --max-cycles N\n";
      std::exit(0);
    } else {
      throw std::invalid_argument("Unknown or incomplete argument: " + arg);
    }
  }
  return opts;
}

uint32_t SelectDestination(const ProbeOptions& opts, uint32_t src,
                           uint32_t sequence) {
  if (opts.num_outputs == 0) {
    return 0;
  }
  if (opts.mode == "single") {
    return 0;
  }
  if (opts.mode == "hotspot") {
    return opts.num_outputs > 20 ? 20 : 0;
  }
  if (opts.mode == "roundrobin") {
    return (src + sequence) % opts.num_outputs;
  }
  throw std::invalid_argument("Unsupported probe mode: " + opts.mode);
}

}  // namespace

int main(int argc, char** argv) {
  const ProbeOptions opts = ParseArgs(argc, argv);

  openbpu::NocVerilatorWrapper::Config config;
  config.num_input_nodes = opts.num_inputs;
  config.num_output_nodes = opts.num_outputs;
  config.packet_bits = opts.packet_bits;
  config.flit_data_bits = opts.flit_data_bits;
  config.dest_bits = opts.dest_bits;
  config.vc_bits = opts.vc_bits;
  config.credit_width = opts.credit_width;
  config.max_pending_per_input = opts.packets_per_source + 4;
  config.max_pending_per_output = opts.active_sources * opts.packets_per_source + 16;
  config.reset_cycles = 5;
  config.fixed_hops = opts.fixed_hops;
  config.min_input_hold_cycles = opts.min_input_hold_cycles;

  openbpu::NocVerilatorWrapper noc(config);
  const uint32_t active_sources =
      opts.active_sources > opts.num_inputs ? opts.num_inputs : opts.active_sources;

  uint64_t packet_id = 1;
  uint64_t injected = 0;
  uint64_t delivered = 0;
  std::map<uint32_t, uint64_t> delivered_by_dest;
  std::vector<SourceStats> source_stats(active_sources);

  for (uint32_t src = 0; src < active_sources; ++src) {
    for (uint32_t seq = 0; seq < opts.packets_per_source; ++seq) {
      openbpu::NocPacket packet;
      packet.packet_id = packet_id++;
      packet.src_id = src;
      packet.dst_id = SelectDestination(opts, src, seq);
      packet.size = opts.packet_size;
      packet.type = openbpu::NocPacketType::kRead;
      if (!noc.push(packet.src_id, packet.dst_id, packet)) {
        std::cerr << "push failed for src=" << src << " dst=" << packet.dst_id
                  << " seq=" << seq << "\n";
        return 2;
      }
      ++injected;
      ++source_stats[src].injected;
    }
  }

  for (uint32_t cycle = 0; cycle < opts.max_cycles; ++cycle) {
    noc.cycle();
    for (uint32_t dst = 0; dst < opts.num_outputs; ++dst) {
      openbpu::NocDeliveredPacket ready;
      while (noc.pop(dst, &ready)) {
        ++delivered;
        ++delivered_by_dest[dst];
        if (ready.packet.src_id < source_stats.size()) {
          ++source_stats[ready.packet.src_id].delivered;
        }
      }
    }
    if (delivered == injected) {
      break;
    }
  }

  const openbpu::NocStatsSnapshot stats = noc.stats();
  std::cout << "probe_mode=" << opts.mode << "\n";
  std::cout << "active_sources=" << active_sources << "\n";
  std::cout << "packets_per_source=" << opts.packets_per_source << "\n";
  std::cout << "injected=" << injected << "\n";
  std::cout << "delivered=" << delivered << "\n";
  std::cout << "undelivered=" << (injected - delivered) << "\n";
  std::cout << "current_cycle=" << stats.current_cycle << "\n";
  std::cout << "avg_latency_cycles=" << stats.avg_latency << "\n";
  std::cout << "avg_hops=" << stats.avg_hops << "\n";
  std::cout << "throughput_packets_per_cycle=" << stats.packets_per_cycle << "\n";
  std::cout << "held_input_cycles=" << noc.held_input_cycles() << "\n";
  std::cout << "repeated_output_cycles_suppressed="
            << noc.repeated_output_cycles_suppressed() << "\n";
  std::cout << "duplicate_output_flits_suppressed="
            << noc.duplicate_output_flits_suppressed() << "\n";
  for (uint32_t src = 0; src < source_stats.size(); ++src) {
    std::cout << "source[" << src << "].injected=" << source_stats[src].injected
              << "\n";
    std::cout << "source[" << src << "].delivered=" << source_stats[src].delivered
              << "\n";
    std::cout << "source[" << src << "].undelivered="
              << (source_stats[src].injected - source_stats[src].delivered) << "\n";
  }
  for (std::map<uint32_t, uint64_t>::const_iterator it = delivered_by_dest.begin();
       it != delivered_by_dest.end(); ++it) {
    std::cout << "delivered_dst[" << it->first << "]=" << it->second << "\n";
  }

  return delivered == injected ? 0 : 1;
}
