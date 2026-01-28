# Minimal cocotb Makefile for this project
TOPLEVEL = OpenBPUNoC
MODULE = test_openbpu
VERILOG_SOURCES = $(abspath $(PWD)/../../generated/OpenBPUNoC.sv)

# Default simulator: verilator. Override with SIM=iverilog or SIM=questa if desired.
# Usage: activate your cocotb-conda env, then `make -C tests/cocotb SIM=verilator`

include $(shell cocotb-config --makefiles)/Makefile.sim
