package openbpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CreditFlowSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  "NoC credit" should "not allow injection when downstream has zero credit" in {
    val params = NoCParams(
      numSMClusters = 1,
      numMCs = 1,
      numSMsPerCluster = 1,
      numL2SlicesPerMC = 1,
      flitWidth = 64,
      bufferDepth = 2,
      numVCs = 1
    )

    test(new OpenBPUNoC(params)) { dut =>
      // 下游尚未提供信用，保持 0 表示不可接收
      dut.io.l2(0).creditIn(0).poke(0.U)

      // 发送一个flit
      dut.io.sm(0).flit.bits.flitType.poke(FlitType.DATA)
      dut.io.sm(0).flit.bits.isLast.poke(true.B)
      dut.io.sm(0).flit.bits.vc.poke(0.U)
      dut.io.sm(0).flit.bits.destId.poke(0.U)
      dut.io.sm(0).flit.bits.data.poke(1.U)
      dut.io.sm(0).flit.valid.poke(true.B)

      dut.clock.step(1)

      // 观察NoC是否阻止该flit进入（即sm端的ready应为 false）
      val ready = dut.io.sm(0).flit.ready.peek().litToBoolean
      assert(!ready, "SM side should not be ready when downstream credit is zero")
    }
  }
}
