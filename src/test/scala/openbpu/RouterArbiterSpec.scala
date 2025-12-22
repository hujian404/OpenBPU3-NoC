package openbpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RouterArbiterSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  "RouterArbiter" should "grant at most one input per output port" in {
    val params = DefaultNoCParams
    test(new RouterArbiter(params, params.numSMRouterPortsIn, params.numSMRouterPortsOut)) { dut =>
      // 驱动多个请求针对同一 output port，从不同 inputs 和 VCs
      // 首先 clear
      for (i <- 0 until params.numSMRouterPortsIn) {
        for (p <- 0 until params.numSMRouterPortsOut) {
          for (vc <- 0 until params.numVCs) {
            dut.io.req(i)(p)(vc).poke(false.B)
          }
        }
      }

      // 让多个输入请求 output 0, vc 0
      for (i <- 0 until params.numSMRouterPortsIn) {
        dut.io.req(i)(0)(0).poke(true.B)
      }

      dut.clock.step(1)

      // 检查同一个输出端口的各输入中，grant字段最多只有一个为true
      val grantsForOut0 = (0 until params.numSMRouterPortsIn).map { i =>
        (0 until params.numVCs).map { vc =>
          dut.io.gnt(i)(0)(vc).peek().litToBoolean
        }.exists(_ == true)
      }

      assert(grantsForOut0.count(_ == true) <= 1)
    }
  }
}
