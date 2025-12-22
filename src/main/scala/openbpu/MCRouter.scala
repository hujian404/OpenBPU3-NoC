package openbpu

import chisel3._
import chisel3.util._

// MC-Router模块，标准路由器流水线
class MCRouter(params: NoCParams) extends Module {
  val io = IO(new Bundle {
    // 输入端口（连接SM-Router）
    val in = Vec(params.numMCRouterPortsIn, Flipped(new NoCInterface(params)))
    
    // 输出端口（连接L2切片）
    val out = Vec(params.numMCRouterPortsOut, new NoCInterface(params))
  })
  
  // ===========================================================================
  // 1. BW: Buffer Write - 缓冲器写入阶段
  // ===========================================================================
  // 创建VC输入缓冲器
  val inputBuffers = Seq.fill(params.numMCRouterPortsIn)(Module(new VCInputBuffer(params)))
  
  // 连接输入缓冲器到外部接口
  for (i <- 0 until params.numMCRouterPortsIn) {
    // 连接Flit输入
    inputBuffers(i).io.in <> io.in(i).flit
    
    // 连接信用信号
    // 现在接口为 per-VC credit Vec，可以直接映射
    inputBuffers(i).io.creditIn := io.in(i).creditOut
    io.in(i).creditIn := inputBuffers(i).io.creditOut
  }
  
  // BW阶段输出：每个输入端口每个VC的Flit和有效信号
  val bwOutFlits = Seq.fill(params.numMCRouterPortsIn)(Seq.fill(params.numVCs)(Wire(new Flit(params))))
  val bwOutValids = Seq.fill(params.numMCRouterPortsIn)(Seq.fill(params.numVCs)(Wire(Bool())))
  
  // 从输入缓冲器获取BW阶段输出
  for (i <- 0 until params.numMCRouterPortsIn) {
    for (vc <- 0 until params.numVCs) {
      bwOutFlits(i)(vc) := inputBuffers(i).io.out(vc).bits
      bwOutValids(i)(vc) := inputBuffers(i).io.out(vc).valid
    }
  }
  
  // ===========================================================================
  // 流水线寄存器：BW → RC
  // ===========================================================================
  val rcInputFlits = Seq.fill(params.numMCRouterPortsIn)(Seq.fill(params.numVCs)(
    RegInit(0.U.asTypeOf(new Flit(params)))
  ))
  val rcInputValids = Seq.fill(params.numMCRouterPortsIn)(Seq.fill(params.numVCs)(RegInit(false.B)))
  
  // 为输入缓冲器输出提供ready信号
  for (i <- 0 until params.numMCRouterPortsIn) {
    for (vc <- 0 until params.numVCs) {
      // 简化：假设流水线始终准备好接收
      inputBuffers(i).io.out(vc).ready := true.B
    }
  }
  
  // 连接BW输出到RC输入寄存器
  for (i <- 0 until params.numMCRouterPortsIn) {
    for (vc <- 0 until params.numVCs) {
      when (inputBuffers(i).io.out(vc).fire) {
        rcInputFlits(i)(vc) := bwOutFlits(i)(vc)
        rcInputValids(i)(vc) := true.B
      } .otherwise {
        rcInputValids(i)(vc) := false.B
      }
    }
  }
  
  // ===========================================================================
  // 2. RC: Route Compute - 路由计算阶段
  // ===========================================================================
  // 创建路由计算模块
  val rc = Module(new MCRouterRC(params))
  
  // 连接RC输入
  for (i <- 0 until params.numMCRouterPortsIn) {
    // 选择一个有效VC发送到RC
    val vcValids = VecInit.tabulate(params.numVCs)(vc => rcInputValids(i)(vc))
    val hasValidFlit = vcValids.asUInt.orR
    val selectedVC = Mux(hasValidFlit, PriorityEncoder(vcValids.asUInt), 0.U)
    
    // 使用Mux1H选择正确的Flit，因为Chisel不允许UInt作为数组索引
    val selectedFlit = Mux1H(Seq.tabulate(params.numVCs) { vc =>
      (selectedVC === vc.U) -> rcInputFlits(i)(vc)
    })

    // RC 输入现在是 Decoupled(new Flit), 填充 bits/valid
    rc.io.in(i).bits := selectedFlit
    rc.io.in(i).valid := hasValidFlit
    // RC 的 ready 由后续逻辑决定；不要在外部驱动 rc.io.in.ready（它是 RC 的输出）
  }
  
  // RC阶段输出：每个输入端口的输出端口号和VC号
    // 使用队列替代 RC->VA 的 RegNext，以提供弹性和反压
    val rcOutQueues = Seq.tabulate(params.numMCRouterPortsIn) { i =>
      Module(new Queue(new RCToVABundle(params, params.numMCRouterPortsOut), 2))
    }

    // 将 RC 的输出组合入队（放在单独循环中以避免在上方循环里引用未定义的 rcOutQueues）
    for (i <- 0 until params.numMCRouterPortsIn) {
      val vcValids = VecInit.tabulate(params.numVCs)(vc => rcInputValids(i)(vc))
      val hasValidFlit = vcValids.asUInt.orR
      val selectedVC = Mux(hasValidFlit, PriorityEncoder(vcValids.asUInt), 0.U)
      val selectedFlit = Mux1H(Seq.tabulate(params.numVCs) { vc => (selectedVC === vc.U) -> rcInputFlits(i)(vc) })

      rcOutQueues(i).io.enq.bits.flit := selectedFlit
      rcOutQueues(i).io.enq.bits.outPort := rc.io.out(i).port
      rcOutQueues(i).io.enq.bits.inVC := selectedVC
      rcOutQueues(i).io.enq.valid := rc.io.in(i).fire
      rc.io.out(i).ready := rcOutQueues(i).io.enq.ready
    }
  
  // ===========================================================================
  // 流水线寄存器：RC → VA
  // ===========================================================================
  val vaInputPortRegs = Seq.fill(params.numMCRouterPortsIn)(Seq.fill(params.numVCs)(
    RegInit(0.U(log2Ceil(params.numMCRouterPortsOut).W))
  ))
  val vaInputVCRegs = Seq.fill(params.numMCRouterPortsIn)(Seq.fill(params.numVCs)(
    RegInit(0.U(log2Ceil(params.numVCs).W))
  ))
  val vaInputValids = Seq.fill(params.numMCRouterPortsIn)(Seq.fill(params.numVCs)(RegInit(false.B)))
  
  // 连接RC输出队列到VA输入寄存器（通过匹配 inVC）
  for (i <- 0 until params.numMCRouterPortsIn) {
    val deqReady = WireInit(false.B)
    for (vc <- 0 until params.numVCs) {
      val headMatch = rcOutQueues(i).io.deq.valid && (rcOutQueues(i).io.deq.bits.inVC === vc.U)
      when (headMatch) {
        vaInputPortRegs(i)(vc) := rcOutQueues(i).io.deq.bits.outPort
        vaInputVCRegs(i)(vc) := rcOutQueues(i).io.deq.bits.inVC
        vaInputValids(i)(vc) := true.B
        deqReady := true.B
      } .otherwise {
        vaInputValids(i)(vc) := false.B
      }
    }
    rcOutQueues(i).io.deq.ready := deqReady
  }
  
  // ===========================================================================
  // 3. VA: VC Allocation - 虚拟通道分配阶段
  // ===========================================================================
  // 创建VC分配模块
  val va = Module(new VCAllocator(params, params.numMCRouterPortsIn))

  // 连接VA输入：当输入有效时，向所有输出VC发出请求，由分配器在输出VC之间选择
  for (i <- 0 until params.numMCRouterPortsIn) {
    for (vc <- 0 until params.numVCs) {
      for (outVC <- 0 until params.numVCs) {
        va.io.req(i)(vc)(outVC).valid := vaInputValids(i)(vc)
      }
    }
  }

  // ========== VA -> SA 队列化 ==========
  val vaOutQueues = Seq.tabulate(params.numMCRouterPortsIn) { i =>
    Module(new Queue(new VAToSABundle(params, params.numMCRouterPortsOut), 2))
  }

  for (i <- 0 until params.numMCRouterPortsIn) {
    val anyGrantPerInVC = VecInit.tabulate(params.numVCs) { vc =>
      val grants = VecInit.tabulate(params.numVCs) { outVC => va.io.grant(i)(vc)(outVC).grant }
      grants.asUInt.orR
    }
    val anyGrant = anyGrantPerInVC.asUInt.orR
    val selInVC = PriorityEncoder(anyGrantPerInVC.asUInt)

    val selOutPerInVC = VecInit.tabulate(params.numVCs) { vc =>
      val grants = VecInit.tabulate(params.numVCs) { outVC => va.io.grant(i)(vc)(outVC).grant }
      PriorityEncoder(grants.asUInt)
    }

    val outPortSel = Mux1H(Seq.tabulate(params.numVCs) { vc => (selInVC === vc.U) -> vaInputPortRegs(i)(vc) })
    val outVCSel = Mux1H(Seq.tabulate(params.numVCs) { vc => (selInVC === vc.U) -> selOutPerInVC(vc) })

    vaOutQueues(i).io.enq.bits.outPort := outPortSel
    vaOutQueues(i).io.enq.bits.inVC := selInVC
    vaOutQueues(i).io.enq.bits.outVC := outVCSel
    vaOutQueues(i).io.enq.valid := anyGrant
  }

  // ===========================================================================
  // 流水线寄存器：VA → SA
  // ===========================================================================
  val saInputPortRegs = Seq.fill(params.numMCRouterPortsIn)(Seq.fill(params.numVCs)(
    RegInit(0.U(log2Ceil(params.numMCRouterPortsOut).W))
  ))
  val saInputVCRegs = Seq.fill(params.numMCRouterPortsIn)(Seq.fill(params.numVCs)(
    RegInit(0.U(log2Ceil(params.numVCs).W))
  ))
  val saInputValids = Seq.fill(params.numMCRouterPortsIn)(Seq.fill(params.numVCs)(RegInit(false.B)))

  // 从 VA 的授权中选择一个输出VC（如果有），将选择的输出VC 写入 SA 寄存器
  for (i <- 0 until params.numMCRouterPortsIn) {
    val deqReady = WireInit(false.B)
    for (vc <- 0 until params.numVCs) {
      val headMatch = vaOutQueues(i).io.deq.valid && (vaOutQueues(i).io.deq.bits.inVC === vc.U)
      when (headMatch) {
        saInputPortRegs(i)(vc) := vaOutQueues(i).io.deq.bits.outPort
        saInputVCRegs(i)(vc) := vaOutQueues(i).io.deq.bits.outVC
        saInputValids(i)(vc) := true.B
        deqReady := true.B
      } .otherwise {
        saInputValids(i)(vc) := false.B
      }
    }
    vaOutQueues(i).io.deq.ready := deqReady
  }
  
  // ===========================================================================
  // 4. SA: Switch Allocation - 开关分配阶段
  // 创建路由器仲裁器
  val arbiter = Module(new RouterArbiter(params, params.numMCRouterPortsIn, params.numMCRouterPortsOut))

  // 连接SA输入
  for (i <- 0 until params.numMCRouterPortsIn) {
    for (vc <- 0 until params.numVCs) {
      for (port <- 0 until params.numMCRouterPortsOut) {
        val req = saInputValids(i)(vc) && (saInputPortRegs(i)(vc) === port.U)
        arbiter.io.req(i)(port)(vc) := req
      }
    }
  }
  
  // ===========================================================================
  // 流水线寄存器：SA → ST
  // ===========================================================================
  // SA -> ST 队列化：将获得开关授权的flit排队传递到ST
  val saOutQueues = Seq.tabulate(params.numMCRouterPortsIn) { i =>
    Module(new Queue(new SAToSTBundle(params, params.numMCRouterPortsOut), 2))
  }

  // 将 SA 授权的 flit 入队到 saOutQueues
  for (i <- 0 until params.numMCRouterPortsIn) {
    val grantVec = VecInit.tabulate(params.numVCs) { vc =>
      val perPortMatches = VecInit.tabulate(params.numMCRouterPortsOut) { port =>
        (saInputPortRegs(i)(vc) === port.U) && arbiter.io.gnt(i)(port)(vc)
      }
      perPortMatches.asUInt.orR && saInputValids(i)(vc)
    }

    val anyGrant = grantVec.asUInt.orR
    val selInVC = PriorityEncoder(grantVec.asUInt)

    val selFlit = Mux1H(Seq.tabulate(params.numVCs) { vc => (selInVC === vc.U) -> rcInputFlits(i)(vc) })
    val selOutPort = Mux1H(Seq.tabulate(params.numVCs) { vc => (selInVC === vc.U) -> saInputPortRegs(i)(vc) })
    val selOutVC = Mux1H(Seq.tabulate(params.numVCs) { vc => (selInVC === vc.U) -> saInputVCRegs(i)(vc) })

    saOutQueues(i).io.enq.bits.flit := selFlit
    saOutQueues(i).io.enq.bits.outPort := selOutPort
    saOutQueues(i).io.enq.bits.inVC := selInVC
    saOutQueues(i).io.enq.bits.outVC := selOutVC
    saOutQueues(i).io.enq.valid := anyGrant
  }
  
  // ===========================================================================
  // 5. ST: Switch Traversal - 切换遍历阶段
  // ===========================================================================
  // 创建路由器输出Mux
  val outputMux = Module(new RouterOutputMux(params, params.numMCRouterPortsIn, params.numMCRouterPortsOut))
  
  // 连接ST输入到输出Mux：从 saOutQueues dequeue 的项填充到对应的 inputVC channel
  for (i <- 0 until params.numMCRouterPortsIn) {
    val deq = saOutQueues(i).io.deq
    val dqValid = deq.valid
    val dqFlit = deq.bits.flit
    val dqInVC = deq.bits.inVC

    for (vc <- 0 until params.numVCs) {
      outputMux.io.in(i)(vc).bits := Mux1H(Seq.tabulate(params.numVCs) { k => (dqInVC === k.U) -> dqFlit })
      outputMux.io.in(i)(vc).valid := dqValid && (dqInVC === vc.U)
    }

    val readyForDequeued = Mux1H(Seq.tabulate(params.numVCs) { k => (dqInVC === k.U) -> outputMux.io.in(i)(k).ready })
    saOutQueues(i).io.deq.ready := readyForDequeued
  }
  
  // 连接仲裁器选择信号到输出Mux
  outputMux.io.sel := arbiter.io.sel
  
  // 连接仲裁器的ready信号
  for (port <- 0 until params.numMCRouterPortsOut) {
    for (vc <- 0 until params.numVCs) {
      arbiter.io.ready(port)(vc) := true.B // 简化：假设下游始终就绪
    }
  }
  
  // ===========================================================================
  // 流水线寄存器：ST → LT
  // ===========================================================================
  val stToLtQueues = Seq.tabulate(params.numMCRouterPortsOut) { port =>
    Seq.tabulate(params.numVCs) { vc => Module(new Queue(new Flit(params), 2)) }
  }

  for (port <- 0 until params.numMCRouterPortsOut) {
    for (vc <- 0 until params.numVCs) {
      stToLtQueues(port)(vc).io.enq.bits := outputMux.io.out(port)(vc).bits
      stToLtQueues(port)(vc).io.enq.valid := outputMux.io.out(port)(vc).valid
      outputMux.io.out(port)(vc).ready := stToLtQueues(port)(vc).io.enq.ready
    }
  }
  
  // ===========================================================================
  // 6. LT: Link Traversal - 链接遍历阶段
  // ===========================================================================
  // 创建虚拟通道合并模块
  val vcMergers = Seq.fill(params.numMCRouterPortsOut)(Module(new VCMerger(params)))
  
  // 连接输出Mux到虚拟通道合并模块
  for (port <- 0 until params.numMCRouterPortsOut) {
    for (vc <- 0 until params.numVCs) {
      vcMergers(port).io.in(vc).bits := stToLtQueues(port)(vc).io.deq.bits
      vcMergers(port).io.in(vc).valid := stToLtQueues(port)(vc).io.deq.valid
      stToLtQueues(port)(vc).io.deq.ready := vcMergers(port).io.in(vc).ready
    }
  }
  
  // 输出寄存器
  val outputRegs = Seq.fill(params.numMCRouterPortsOut)(RegInit(0.U.asTypeOf(new Flit(params))))
  val outputValidRegs = Seq.fill(params.numMCRouterPortsOut)(RegInit(false.B))
  
  // 连接虚拟通道合并模块到输出寄存器
  for (port <- 0 until params.numMCRouterPortsOut) {
    when (vcMergers(port).io.out.fire) {
      outputRegs(port) := vcMergers(port).io.out.bits
      outputValidRegs(port) := true.B
    } .elsewhen (io.out(port).flit.fire) {
      outputValidRegs(port) := false.B
    }
    
    // 连接输出接口
    io.out(port).flit.bits := outputRegs(port)
    io.out(port).flit.valid := outputValidRegs(port)
    vcMergers(port).io.out.ready := !outputValidRegs(port) || io.out(port).flit.ready
  }
  
  // 信用信号处理（输出端口到下游的信用）
  for (port <- 0 until params.numMCRouterPortsOut) {
    // 初始化每个输出端口的每个VC信用为缓冲深度
    io.out(port).creditOut := VecInit.fill(params.numVCs)(params.bufferDepth.U)
  }
}

// 伴生对象，提供默认构造函数
object MCRouter {
  def apply(params: NoCParams = DefaultNoCParams): MCRouter = Module(new MCRouter(params))
}
