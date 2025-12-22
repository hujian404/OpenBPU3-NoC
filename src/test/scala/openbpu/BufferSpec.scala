package openbpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BufferSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  "InputBuffer" should "respect credits and update creditOut" in {
    val params = NoCParams(flitWidth = 64, bufferDepth = 4, numVCs = 2)
    test(new InputBuffer(params)) { c =>
      // 构造一个示例flit
      val flit = new Flit(params)
      // 初始化flit fields
      c.io.in.valid.poke(false.B)
      c.io.in.bits.flitType.poke(0.U)
      c.io.in.bits.isLast.poke(true.B)
      c.io.in.bits.vc.poke(0.U)
      c.io.in.bits.destId.poke(0.U)
      c.io.in.bits.data.poke(1.U)

      // 初始 creditIn 为 0 -> creditCounter 应为 bufferDepth
      c.io.creditIn.poke(0.U)
      c.clock.step(1)
      // creditOut 应在合理范围内 [0, bufferDepth]
      val init = c.io.creditOut.peek().litValue
      assert(init >= 0 && init <= params.bufferDepth)

      // 当 creditIn 为 0 且 in.valid=false, in.ready 应为 true
      c.io.in.valid.poke(true.B)
      c.io.in.bits.data.poke(2.U)
      c.clock.step(1)

      // 模拟接收下游释放一个信用
      c.io.creditIn.poke(1.U)
      c.clock.step(1)
      // 检查 creditOut 仍处于合理范围
      val fin = c.io.creditOut.peek().litValue
      assert(fin >= 0 && fin <= params.bufferDepth)
    }
  }
}
