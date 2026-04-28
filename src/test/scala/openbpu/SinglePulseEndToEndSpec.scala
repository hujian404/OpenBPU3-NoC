package openbpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SinglePulseEndToEndSpec
    extends AnyFlatSpec
    with ChiselScalatestTester
    with Matchers {
  "OpenBPUNoC" should "deliver a one-cycle input pulse exactly once" in {
    val params = NoCParams(
      numSMClusters = 1,
      numMCs = 1,
      numSMsPerCluster = 1,
      numL2SlicesPerMC = 1,
      flitWidth = 64,
      bufferDepth = 4,
      numVCs = 2
    )

    test(new OpenBPUNoC(params)) { dut =>
      dut.io.sm(0).flit.valid.poke(false.B)
      dut.io.l2(0).flit.ready.poke(true.B)

      for (vc <- 0 until params.numVCs) {
        dut.io.sm(0).creditOut(vc).poke(params.bufferDepth.U)
        dut.io.l2(0).creditIn(vc).poke(params.bufferDepth.U)
      }

      dut.clock.step()
      dut.io.sm(0).flit.bits.flitType.poke(FlitType.DATA)
      dut.io.sm(0).flit.bits.isLast.poke(true.B)
      dut.io.sm(0).flit.bits.vc.poke(0.U)
      dut.io.sm(0).flit.bits.destId.poke(0.U)
      dut.io.sm(0).flit.bits.data.poke(0x55.U)
      dut.io.sm(0).flit.valid.poke(true.B)
      dut.io.sm(0).flit.ready.expect(true.B)
      dut.clock.step()
      dut.io.sm(0).flit.valid.poke(false.B)

      var hitCount = 0
      for (_ <- 0 until 40) {
        if (dut.io.l2(0).flit.valid.peek().litToBoolean) {
          dut.io.l2(0).flit.bits.data.peek().litValue should be(0x55)
          hitCount += 1
        }
        dut.clock.step()
      }

      hitCount should be(1)
    }
  }
}
