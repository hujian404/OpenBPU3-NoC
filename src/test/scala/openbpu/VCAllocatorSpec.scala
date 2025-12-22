package openbpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class VCAllocatorSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  "VCAllocator" should "grant a single requester when only one requests" in {
    val params = DefaultNoCParams
    test(new VCAllocator(params, 2)) { dut =>
      // 简单激励：设置一个输入VC请求某输出VC，检查grant信号
      // 设置所有请求为 false
      for (i <- 0 until 2) {
        for (vc <- 0 until params.numVCs) {
          for (outVC <- 0 until params.numVCs) {
            dut.io.req(i)(vc)(outVC).valid.poke(false.B)
          }
        }
      }

      // 发出单一请求： input 0, vc 0 请求 outVC 0
      dut.io.req(0)(0)(0).valid.poke(true.B)
      dut.clock.step(1)

      // 观察 grant 输出：至少一个 grant 应该被置位
      val g = dut.io.grant(0)(0)(0).grant.peek().litToBoolean
      assert(g == true)
    }
  }
}
