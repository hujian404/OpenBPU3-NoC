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
  
  // FIFO队列
  val fifo = Module(new Queue(new Flit(params), params.bufferDepth))
  
  // 信用计数
  val creditCounter = RegInit(params.bufferDepth.U(params.creditWidth.W))
  
  // 信用更新逻辑
  when (io.creditIn =/= 0.U) {
    creditCounter := creditCounter + io.creditIn
  }
  
  when (io.in.fire) {
    creditCounter := creditCounter - 1.U
  }
  
  // FIFO接口连接
  fifo.io.enq <> io.in
  io.out <> fifo.io.deq
  
  // 向上游发送可用信用
  io.creditOut := creditCounter
  
  // 确保FIFO不溢出（通过信用控制）
  io.in.ready := creditCounter > 0.U
}

// 虚拟通道输入缓冲器数组
class VCInputBuffer(params: NoCParams) extends Module {
  val io = IO(new Bundle {
    // 上游接口
    val in = Flipped(Decoupled(new Flit(params)))
    
    // 下游接口（按VC分开）
    val out = Vec(params.numVCs, Decoupled(new Flit(params)))
    
    // 信用信号
    val creditIn = Input(Vec(params.numVCs, UInt(params.creditWidth.W)))
    val creditOut = Output(UInt(params.creditWidth.W))
  })
  
  // 创建多个虚拟通道的输入缓冲器
  val buffers = Seq.fill(params.numVCs)(Module(new InputBuffer(params)))
  
  // VC分配逻辑（简单的轮询分配）
  val vcSelector = RegInit(0.U(log2Ceil(params.numVCs).W))
  
  // 查找可用的VC
  val availableVC = Wire(Vec(params.numVCs, Bool()))
  for (i <- 0 until params.numVCs) {
    availableVC(i) := buffers(i).io.in.ready
  }
  
  val hasAvailableVC = availableVC.asUInt.orR
  val selectedVC = MuxCase(0.U, availableVC.zipWithIndex.map { case (avail, i) => (avail, i.U) })
  
  // 连接上游接口到选中的VC
  for (i <- 0 until params.numVCs) {
    buffers(i).io.in.valid := io.in.valid && (selectedVC === i.U)
    buffers(i).io.in.bits := io.in.bits
  }
  
  io.in.ready := hasAvailableVC
  
  // 连接下游接口
  for (i <- 0 until params.numVCs) {
    io.out(i) <> buffers(i).io.out
    buffers(i).io.creditIn := io.creditIn(i)
  }
  
  // 信用输出（聚合所有VC的信用）
  io.creditOut := buffers.map(_.io.creditOut).reduce(_ + _)
  
  // 更新VC选择器
  when (io.in.fire) {
    vcSelector := Mux(vcSelector === (params.numVCs - 1).U, 0.U, vcSelector + 1.U)
  }
}
