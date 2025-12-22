package openbpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RouterEndToEndSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  "OpenBPUNoC" should "forward a flit from SM to L2 end-to-end" in {
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
      // 下游L2一直准备好接收
      for (i <- 0 until params.numL2Slices) {
        dut.io.l2(i).flit.ready.poke(true.B)
      }

      // 初始化所有下游信用为 bufferDepth，确保管道可以通行
      for (i <- 0 until params.numL2Slices) {
        for (vc <- 0 until params.numVCs) {
          dut.io.l2(i).creditIn(vc).poke(params.bufferDepth.U)
        }
      }

      // 源端口也设置 creditOut 为 bufferDepth（常见的初始条件）
      for (i <- 0 until params.numSMs) {
        for (vc <- 0 until params.numVCs) {
          dut.io.sm(i).creditOut(vc).poke(params.bufferDepth.U)
        }
      }

      // 发送一个简单 Flit 从 SM(0)
      dut.io.sm(0).flit.bits.flitType.poke(FlitType.DATA)
      dut.io.sm(0).flit.bits.isLast.poke(true.B)
      dut.io.sm(0).flit.bits.vc.poke(0.U)
      dut.io.sm(0).flit.bits.destId.poke(0.U)
      dut.io.sm(0).flit.bits.data.poke(0x42.U)
      dut.io.sm(0).flit.valid.poke(true.B)

      // advance until L2 sees it
      var seen = false
      var cycle = 0
      for (_ <- 0 until 30) {
        dut.clock.step(1)
        cycle += 1
        if (dut.io.l2(0).flit.valid.peek().litToBoolean) {
          seen = true
          // 验证内容
          dut.io.l2(0).flit.bits.data.peek().litValue should be (0x42)
        }
      }

      assert(seen, "L2 did not observe the forwarded flit within 30 cycles")
    }
  }
}
