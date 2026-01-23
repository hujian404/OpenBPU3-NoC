import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge, Timer


@cocotb.test()
async def test_reset_and_clock(dut):
    """Basic smoke test: run clock, pulse reset, and wait some cycles."""
    cocotb.start_soon(Clock(dut.clock, 10, units="ns").start())

    dut.reset.value = 1
    await Timer(100, units="ns")
    dut.reset.value = 0

    for _ in range(10):
        await RisingEdge(dut.clock)

    cocotb.log.info("Basic reset+clock smoke test finished")
