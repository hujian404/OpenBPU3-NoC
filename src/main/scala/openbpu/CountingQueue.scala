package openbpu

import chisel3._
import chisel3.util._

// 带占用计数的 Queue 封装，用于更精确地导出信用和做形式化断言
class CountingQueue[T <: Data](gen: T, entries: Int) extends Module {
  require(entries > 0, "CountingQueue entries must be > 0")

  private val countWidth = log2Ceil(entries + 1)

  val io = IO(new Bundle {
    val enq   = Flipped(Decoupled(gen))
    val deq   = Decoupled(gen)
    // 当前队列中已经占用的元素个数
    val count = Output(UInt(countWidth.W))
  })

  // 底层使用标准 Queue
  private val q = Module(new Queue(gen, entries))

  q.io.enq <> io.enq
  io.deq   <> q.io.deq

  // 维护占用计数：enq.fire 增 1，deq.fire 减 1
  val countReg = RegInit(0.U(countWidth.W))
  val inc      = io.enq.fire
  val dec      = io.deq.fire

  val next = countReg + inc.asUInt - dec.asUInt
  val max  = entries.U(countWidth.W)

  // 限制在 [0, entries]，并提供简单断言
  when (next > max) {
    countReg := max
  } .otherwise {
    countReg := next
  }

  io.count := countReg

  // 安全断言（仿真/形式化专用）
  assert(countReg <= max, "CountingQueue occupancy overflow")
  when (countReg === 0.U) {
    // 队列空时不应有 deq.valid
    assert(!io.deq.valid, "CountingQueue reports empty but deq.valid is high")
  }
}


