import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge, Timer


@cocotb.test()
async def test_reset_and_clock(dut):
    """Basic smoke test: run clock, pulse reset, and wait some cycles."""
    cocotb.start_soon(Clock(dut.clock, 10, unit="ns").start())

    dut.reset.value = 1
    await Timer(100, unit="ns")
    dut.reset.value = 0

    for _ in range(10):
        await RisingEdge(dut.clock)

    cocotb.log.info("Basic reset+clock smoke test finished")


@cocotb.test()
async def test_sm0_to_l2_delivery(dut):
    """Inject a single flit at SM0 and check it arrives at L2 index 40."""
    cocotb.start_soon(Clock(dut.clock, 10, unit="ns").start())

    # reset
    dut.reset.value = 1
    await Timer(100, unit="ns")
    dut.reset.value = 0
    await RisingEdge(dut.clock)

    # ensure credits presented from SM-side so injection can occur
    try:
        dut.io_sm_0_creditOut_0.value = 0x10
        dut.io_sm_0_creditOut_1.value = 0x10
    except Exception:
        # if signals are named differently in this build, just continue and rely on ready
        pass

    # drive L2 side ready/credits so DUT can forward flit out
    try:
        dut.io_l2_40_creditIn_0.value = 0x10
        dut.io_l2_40_creditIn_1.value = 0x10
        dut.io_l2_40_flit_ready.value = 1
    except Exception:
        # if these signals are named differently, test will fail later with helpful message
        pass

    # prepare flit fields
    flit_type = 0  # DATA
    is_last = 1
    vc = 0
    dest = 40
    data = 0x123456789AB

    # drive flit bits
    dut.io_sm_0_flit_bits_flitType.value = flit_type
    dut.io_sm_0_flit_bits_isLast.value = is_last
    dut.io_sm_0_flit_bits_vc.value = vc
    dut.io_sm_0_flit_bits_destId.value = dest
    dut.io_sm_0_flit_bits_data.value = data

    # assert valid until accepted
    dut.io_sm_0_flit_valid.value = 1
    accepted = False
    # monitor handshake for a short window and log signals each cycle
    last_accepted_cycle = None
    for cycle in range(300):
        await RisingEdge(dut.clock)
        try:
            sm_ready = int(dut.io_sm_0_flit_ready.value)
        except Exception:
            sm_ready = None
        try:
            sm_credit_out0 = int(dut.io_sm_0_creditOut_0.value)
            sm_credit_out1 = int(dut.io_sm_0_creditOut_1.value)
        except Exception:
            sm_credit_out0 = sm_credit_out1 = None
        try:
            l2_ready = int(dut.io_l2_40_flit_ready.value)
        except Exception:
            l2_ready = None
        try:
            l2_valid = int(dut.io_l2_40_flit_valid.value)
        except Exception:
            l2_valid = None
        try:
            l2_credit_out0 = int(dut.io_l2_40_creditOut_0.value)
            l2_credit_out1 = int(dut.io_l2_40_creditOut_1.value)
        except Exception:
            l2_credit_out0 = l2_credit_out1 = None
        try:
            sm_credit_in0 = int(dut.io_sm_0_creditIn_0.value)
            sm_credit_in1 = int(dut.io_sm_0_creditIn_1.value)
        except Exception:
            sm_credit_in0 = sm_credit_in1 = None

        cocotb.log.info(
            f"cycle {cycle}: sm_ready={sm_ready} sm_creditOut=({sm_credit_out0},{sm_credit_out1}) sm_creditIn=({sm_credit_in0},{sm_credit_in1}) "
            f"l2_ready={l2_ready} l2_valid={l2_valid} l2_creditOut=({l2_credit_out0},{l2_credit_out1})"
        )

        if sm_ready == 1:
            accepted = True
            last_accepted_cycle = cycle
            # do not break; continue logging to trace internal progress

    dut.io_sm_0_flit_valid.value = 0

    if not accepted:
        cocotb.log.warning("Flit was not accepted by SM input (ready never asserted)")

    # wait for arrival at L2 port with index 40
    arrived = False
    for _ in range(1000):
        await RisingEdge(dut.clock)
        try:
            if int(dut.io_l2_40_flit_valid.value) == 1:
                arrived = True
                break
        except Exception:
            # port may be named differently - fail the test explicitly
            assert False, "Expected signal io_l2_40_flit_valid not found on DUT"

    assert arrived, "Flit did not arrive at L2[40] within timeout"

    # verify fields
    assert int(dut.io_l2_40_flit_bits_flitType.value) == flit_type
    assert int(dut.io_l2_40_flit_bits_isLast.value) == is_last
    assert int(dut.io_l2_40_flit_bits_vc.value) == vc
    assert int(dut.io_l2_40_flit_bits_destId.value) == dest
    assert int(dut.io_l2_40_flit_bits_data.value) == data

    cocotb.log.info("SM0->L2[40] flit delivered and verified")
