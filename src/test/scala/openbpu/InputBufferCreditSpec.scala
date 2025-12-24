package openbpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class InputBufferCreditSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  "InputBuffer" should "start with full credit and never overflow" in {
    val params = NoCParams(flitWidth = 64, bufferDepth = 4, numVCs = 1)
    test(new InputBuffer(params)) { c =>
      // 初始状态：creditCounter 应等于 bufferDepth
      c.io.creditIn.poke(0.U)
      c.io.in.valid.poke(false.B)
      c.clock.step(1)

      val initCredit = c.io.creditOut.peek().litValue
      assert(initCredit == params.bufferDepth.toLong)

      // 连续注入 flit，直到用尽本地 credit
      c.io.in.valid.poke(true.B)
      c.io.in.bits.flitType.poke(FlitType.DATA)
      c.io.in.bits.isLast.poke(true.B)
      c.io.in.bits.vc.poke(0.U)
      c.io.in.bits.destId.poke(0.U)
      c.io.in.bits.data.poke(0x1.U)

      var accepted = 0
      for (_ <- 0 until params.bufferDepth + 2) {
        val ready = c.io.in.ready.peek().litToBoolean
        if (ready) {
          accepted += 1
        }
        c.clock.step(1)
        val credit = c.io.creditOut.peek().litValue
        assert(credit <= params.bufferDepth.toLong)
      }

      // 不应允许超过 bufferDepth 次成功接收
      assert(accepted <= params.bufferDepth)

      // 注入下游释放的 credit，验证不会溢出
      c.io.in.valid.poke(false.B)
      c.io.creditIn.poke(2.U)
      c.clock.step(1)
      val creditAfter = c.io.creditOut.peek().litValue
      assert(creditAfter <= params.bufferDepth.toLong)
    }
  }
}


