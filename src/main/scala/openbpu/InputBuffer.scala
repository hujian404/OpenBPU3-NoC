package openbpu

import chisel3._
import chisel3.util._

// 输入缓冲器模块，支持信用制流量控制
class InputBuffer(params: NoCParams) extends Module {
  val io = IO(new Bundle {
    // 上游接口（接收Flit）
    val in = Flipped(Decoupled(new Flit(params)))
    
    // 下游接口（发送Flit）
    val out = Decoupled(new Flit(params))
    
    // 信用信号
    val creditIn = Input(UInt(params.creditWidth.W))   // 从下游接收的信用
    val creditOut = Output(UInt(params.creditWidth.W)) // 向上游发送的信用
  })
  
  // FIFO队列。对输入缓冲来说，可用信用应当等价于“本地剩余空槽”，
  // 而不是把下游 creditIn 当作逐拍增量重复累加。
  val fifo = Module(new CountingQueue(new Flit(params), params.bufferDepth))

  // 明确连接FIFO的enq接口，避免 <> 带来的重复驱动
  fifo.io.enq.bits := io.in.bits
  // 只有在有可用信用时才允许入队
  fifo.io.enq.valid := io.in.valid
  io.in.ready := fifo.io.enq.ready

  // 下游连接
  io.out <> fifo.io.deq

  val freeSlots = params.bufferDepth.U - fifo.io.count
  val creditMax = ((1 << params.creditWidth) - 1).U(params.creditWidth.W)
  io.creditOut := Mux(freeSlots > creditMax, creditMax, freeSlots)(params.creditWidth - 1, 0)

  // 当前实现中本地输入缓冲只依赖自身占用导出信用。
  // 保留 creditIn 端口是为了接口兼容和后续扩展。
  dontTouch(io.creditIn)
}

// 虚拟通道输入缓冲器数组
class VCInputBuffer(params: NoCParams) extends Module {
  val io = IO(new Bundle {
    // 上游接口
    val in = Flipped(Decoupled(new Flit(params)))
    
    // 下游接口（按VC分开）
    val out = Vec(params.numVCs, Decoupled(new Flit(params)))
    
    // 信用信号：每个VC独立的信用输出（向上游）
    val creditIn = Input(Vec(params.numVCs, UInt(params.creditWidth.W)))
    val creditOut = Output(Vec(params.numVCs, UInt(params.creditWidth.W)))
  })
  
  // 创建多个虚拟通道的输入缓冲器
  val buffers = Seq.fill(params.numVCs)(Module(new InputBuffer(params)))
  
  val selectedVC = io.in.bits.vc
  val selectedVCReady = WireDefault(false.B)

  // 连接上游接口到 Flit 自带的 VC，对齐整个路由流水线的 VC 语义。
  for (i <- 0 until params.numVCs) {
    buffers(i).io.in.valid := io.in.valid && (selectedVC === i.U)
    buffers(i).io.in.bits := io.in.bits
    when (selectedVC === i.U) {
      selectedVCReady := buffers(i).io.in.ready
    }
  }

  io.in.ready := selectedVCReady
  
  // 连接下游接口并逐VC传递信用
  for (i <- 0 until params.numVCs) {
    io.out(i) <> buffers(i).io.out
    buffers(i).io.creditIn := io.creditIn(i)
    io.creditOut(i) := buffers(i).io.creditOut
  }
}
