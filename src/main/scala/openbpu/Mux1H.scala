package openbpu

import chisel3._
import chisel3.util._

// 1-热选择多路复用器
class Mux1H[T <: Data](gen: T, n: Int) extends Module {
  val io = IO(new Bundle {
    val in = Input(Vec(n, gen))  // 输入向量
    val sel = Input(Vec(n, Bool()))  // 1-热选择信号
    val out = Output(gen)  // 输出
  })
  
  // 检查选择信号是否为1-热编码（可选）
  // assert(PopCount(io.sel) <= 1.U, "Mux1H: sel must be 1-hot or zero")
  
  // 实现多路复用逻辑
  io.out := MuxCase(0.U.asTypeOf(gen), io.sel.zipWithIndex.map { case (s, i) => (s, io.in(i)) })
}
