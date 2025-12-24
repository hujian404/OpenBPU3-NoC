package openbpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CountingQueueSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  "CountingQueue" should "track occupancy correctly" in {
    test(new CountingQueue(UInt(8.W), 2)) { c =>
      // 初始应为空
      c.io.enq.valid.poke(false.B)
      c.io.deq.ready.poke(false.B)
      c.clock.step()
      assert(c.io.count.peek().litValue == 0L)

      // 入队一个
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.poke(1.U)
      c.io.deq.ready.poke(false.B)
      c.clock.step()
      assert(c.io.count.peek().litValue == 1L)

      // 再入队一个，达到满
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.poke(2.U)
      c.clock.step()
      assert(c.io.count.peek().litValue == 2L)

      // 出队一个
      c.io.enq.valid.poke(false.B)
      c.io.deq.ready.poke(true.B)
      c.clock.step()
      assert(c.io.count.peek().litValue == 1L)

      // 再出队一个，回到空
      c.clock.step()
      assert(c.io.count.peek().litValue == 0L)
    }
  }
}


