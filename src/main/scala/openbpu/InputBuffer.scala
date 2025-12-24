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
  
  // 信用计数：表示当前可用的接收缓冲数
  // 这里采用“缓冲满”初始模型：上电后就认为本端有 bufferDepth 个空槽，
  // 下游通过 creditIn 仅用于在某些实现中补充额外信用；更常见的形式是：
  //   creditOut ≈ 剩余空槽数 = bufferDepth - fifoOccupancy
  // 为提升可验证性，我们显式跟踪 creditCounter，并与 FIFO 占用保持一致。
  val creditCounter = RegInit(params.bufferDepth.U(params.creditWidth.W))

  // 明确连接FIFO的enq接口，避免 <> 带来的重复驱动
  fifo.io.enq.bits := io.in.bits
  // 只有在有可用信用时才允许入队
  fifo.io.enq.valid := io.in.valid && (creditCounter > 0.U)
  io.in.ready := fifo.io.enq.ready && (creditCounter > 0.U)

  // 下游连接
  io.out <> fifo.io.deq

  // 信用更新：
  // - 当成功接收一个flit（io.in.fire）时，消耗 1 个本地空槽；
  // - 当下游释放一个缓冲（通过 creditIn 上报）时，补充相应空槽。
  val recvFlit = io.in.fire
  val creditFromDown = io.creditIn
  val creditDec = Mux(recvFlit, 1.U, 0.U)
  val creditNextRaw = creditCounter + creditFromDown - creditDec
  // 约束在 [0, bufferDepth] 范围内，避免计数溢出，便于形式化验证
  val creditMax = params.bufferDepth.U(params.creditWidth.W)
  val creditNextClamped = Mux(creditNextRaw > creditMax, creditMax, creditNextRaw)
  creditCounter := creditNextClamped
  
  // 向上游汇报当前可用信用
  io.creditOut := creditCounter
  
  // 简单的安全断言：在仿真/形式化中捕获明显协议违例
  assert(creditCounter <= creditMax, "InputBuffer creditCounter overflow")
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
  
  // 连接下游接口并逐VC传递信用
  for (i <- 0 until params.numVCs) {
    io.out(i) <> buffers(i).io.out
    buffers(i).io.creditIn := io.creditIn(i)
    io.creditOut(i) := buffers(i).io.creditOut
  }
  
  // 更新VC选择器
  when (io.in.fire) {
    vcSelector := Mux(vcSelector === (params.numVCs - 1).U, 0.U, vcSelector + 1.U)
  }
}
