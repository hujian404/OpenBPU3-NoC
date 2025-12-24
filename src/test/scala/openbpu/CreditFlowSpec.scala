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

      // 在新的实现中，SM 端 ready 由本地 InputBuffer creditCounter 和管线空闲共同决定。
      // 初始 creditCounter = bufferDepth，本地仍可接受若干 flit，但这些 flit 不会越过出口因为下游 creditIn=0。
      // 因此这里不再强制 ready==false，而是检查“L2 在缺乏信用时不会看到 flit”。
      var seenAtL2 = false
      for (_ <- 0 until 10) {
        if (dut.io.l2(0).flit.valid.peek().litToBoolean) {
          seenAtL2 = true
        }
        dut.clock.step(1)
      }
      assert(!seenAtL2, "L2 should not observe flits when its creditIn is zero")
    }
  }
}
