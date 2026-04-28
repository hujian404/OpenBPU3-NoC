package openbpu

import chisel3._
import chisel3.util._

// SM-Router模块，明确实现标准路由器流水线阶段
class SMRouter(params: NoCParams) extends Module {
  val io = IO(new Bundle {
    // 输入端口（连接SM）
    val in = Vec(params.numSMRouterPortsIn, Flipped(new NoCInterface(params)))
    
    // 输出端口（连接MC-Router）
    val out = Vec(params.numSMRouterPortsOut, new NoCInterface(params))
  })
  
  // ===========================================================================
  // 1. BW: Buffer Write - 缓冲器写入阶段
  // ===========================================================================
  // 创建VC输入缓冲器
  val inputBuffers = Seq.fill(params.numSMRouterPortsIn)(Module(new VCInputBuffer(params)))
  
  // 连接输入缓冲器到外部接口
  for (i <- 0 until params.numSMRouterPortsIn) {
    // 连接Flit输入
    inputBuffers(i).io.in <> io.in(i).flit
    
    // 连接信用信号
    // 现在接口为 per-VC credit Vec，可以直接映射
    inputBuffers(i).io.creditIn := io.in(i).creditOut
    io.in(i).creditIn := inputBuffers(i).io.creditOut
  }
  
  // BW阶段输出：每个输入端口每个VC的Flit和有效信号
  val bwOutFlits = Seq.fill(params.numSMRouterPortsIn)(Seq.fill(params.numVCs)(Wire(new Flit(params))))
  val bwOutValids = Seq.fill(params.numSMRouterPortsIn)(Seq.fill(params.numVCs)(Wire(Bool())))
  
  // 从输入缓冲器获取BW阶段输出
  for (i <- 0 until params.numSMRouterPortsIn) {
    for (vc <- 0 until params.numVCs) {
      bwOutFlits(i)(vc) := inputBuffers(i).io.out(vc).bits
      bwOutValids(i)(vc) := inputBuffers(i).io.out(vc).valid
    }
  }
  
  // ===========================================================================
  // 流水线寄存器：BW → RC
  // ===========================================================================
  val rcInputFlits = Seq.fill(params.numSMRouterPortsIn)(Seq.fill(params.numVCs)(
    RegInit(0.U.asTypeOf(new Flit(params)))
  ))
  val rcInputValids = Seq.fill(params.numSMRouterPortsIn)(Seq.fill(params.numVCs)(RegInit(false.B)))

  // 每个输入端口的 RC 轮询指针（VC 选择用）
  val rcRrPtr = Seq.fill(params.numSMRouterPortsIn)(RegInit(0.U(log2Ceil(params.numVCs).W)))

  // VA 输入寄存器（由 RC->VA 的 dequeue 填充）
  val vaInputPortRegs = Seq.fill(params.numSMRouterPortsIn)(Seq.fill(params.numVCs)(
    RegInit(0.U(log2Ceil(params.numSMRouterPortsOut).W))
  ))
  val vaInputVCRegs = Seq.fill(params.numSMRouterPortsIn)(Seq.fill(params.numVCs)(
    RegInit(0.U(log2Ceil(params.numVCs).W))
  ))
  val vaInputFlitRegs = Seq.fill(params.numSMRouterPortsIn)(Seq.fill(params.numVCs)(
    RegInit(0.U.asTypeOf(new Flit(params)))
  ))
  val vaInputValids = Seq.fill(params.numSMRouterPortsIn)(Seq.fill(params.numVCs)(RegInit(false.B)))
  
  // ===========================================================================
  // 2. RC: Route Compute - 路由计算阶段
  // ===========================================================================
  // 创建路由计算模块
  val rc = Module(new SMRouterRC(params))
  val rcSelectedVC = Seq.fill(params.numSMRouterPortsIn)(Wire(UInt(log2Ceil(params.numVCs).W)))
  val rcHasValidFlit = Seq.fill(params.numSMRouterPortsIn)(Wire(Bool()))
  val rcConsume = Seq.fill(params.numSMRouterPortsIn)(Seq.fill(params.numVCs)(Wire(Bool())))
  
  // 连接RC输入
  for (i <- 0 until params.numSMRouterPortsIn) {
    // 选择一个有效VC发送到RC（轮询避免饥饿）
    val vcValids = VecInit.tabulate(params.numVCs)(vc => rcInputValids(i)(vc))
    val hasValidFlit = vcValids.asUInt.orR

    val masked = VecInit.tabulate(params.numVCs)(k => vcValids(k) && (k.U >= rcRrPtr(i)))
    val hasMasked = masked.asUInt.orR
    val selectedVC = Mux(hasMasked, PriorityEncoder(masked.asUInt), PriorityEncoder(vcValids.asUInt))
    
    val selectedFlit = Mux1H(Seq.tabulate(params.numVCs) { vc =>
      (selectedVC === vc.U) -> rcInputFlits(i)(vc)
    })

    rc.io.in(i).bits := selectedFlit
    rc.io.in(i).valid := hasValidFlit
    rcSelectedVC(i) := selectedVC
    rcHasValidFlit(i) := hasValidFlit

    // 成功发送后更新轮询指针
    when (rc.io.in(i).fire) {
      rcRrPtr(i) := Mux(selectedVC === (params.numVCs - 1).U, 0.U, selectedVC + 1.U)
    }

    for (vc <- 0 until params.numVCs) {
      rcConsume(i)(vc) := rc.io.in(i).fire && (selectedVC === vc.U)
    }
  }

  // RC 输入寄存器只在槽位空闲时从 BW 接新 flit，并在被 RC 消费前保持稳定。
  for (i <- 0 until params.numSMRouterPortsIn) {
    for (vc <- 0 until params.numVCs) {
      val slotWillFree = rcConsume(i)(vc)
      inputBuffers(i).io.out(vc).ready := !rcInputValids(i)(vc) || slotWillFree

      when (inputBuffers(i).io.out(vc).fire) {
        rcInputFlits(i)(vc) := bwOutFlits(i)(vc)
        rcInputValids(i)(vc) := true.B
      } .elsewhen (slotWillFree) {
        rcInputValids(i)(vc) := false.B
      }
    }
  }

  // 使用队列替代 RC->VA 的 RegNext，以提供弹性和反压
  val rcOutQueues = Seq.tabulate(params.numSMRouterPortsIn) { i =>
    Module(new Queue(new RCToVABundle(params, params.numSMRouterPortsOut), 2))
  }

  // 将 RC 的输出组合入队（放在单独循环中以避免在上方循环里引用未定义的 rcOutQueues）
  for (i <- 0 until params.numSMRouterPortsIn) {
    val selectedVC = rcSelectedVC(i)
    val selectedFlit = Mux1H(Seq.tabulate(params.numVCs) { vc => (selectedVC === vc.U) -> rcInputFlits(i)(vc) })

    rcOutQueues(i).io.enq.bits.flit := selectedFlit
    rcOutQueues(i).io.enq.bits.outPort := rc.io.out(i).port
    rcOutQueues(i).io.enq.bits.inVC := selectedVC
    rcOutQueues(i).io.enq.valid := rc.io.in(i).fire
    rc.io.out(i).ready := rcOutQueues(i).io.enq.ready
  }
  
  // ========== VA -> SA 的 dequeue 到 SA 寄存器 ==========
  // 流水线寄存器：VA → SA
  val saInputPortRegs = Seq.fill(params.numSMRouterPortsIn)(Seq.fill(params.numVCs)(
    RegInit(0.U(log2Ceil(params.numSMRouterPortsOut).W))
  ))
  val saInputVCRegs = Seq.fill(params.numSMRouterPortsIn)(Seq.fill(params.numVCs)(
    RegInit(0.U(log2Ceil(params.numVCs).W))
  ))
  val saInputFlitRegs = Seq.fill(params.numSMRouterPortsIn)(Seq.fill(params.numVCs)(
    RegInit(0.U.asTypeOf(new Flit(params)))
  ))
  val saInputValids = Seq.fill(params.numSMRouterPortsIn)(Seq.fill(params.numVCs)(RegInit(false.B)))

  // 为 VA -> SA 准备的输出队列（在稍后由 VA 阶段入队）
  val vaOutQueues = Seq.tabulate(params.numSMRouterPortsIn) { i =>
    Module(new Queue(new VAToSABundle(params, params.numSMRouterPortsOut), 2))
  }
  
  // ===========================================================================
  // 3. VA: VC Allocation - 虚拟通道分配阶段
  // ===========================================================================
  // 创建VC分配模块
  val va = Module(new VCAllocator(params, params.numSMRouterPortsIn))
  val vaConsume = Seq.fill(params.numSMRouterPortsIn)(Seq.fill(params.numVCs)(Wire(Bool())))

  // 连接VA输入：当输入有效时，向所有输出VC发出请求，由分配器在输出VC之间选择
  for (i <- 0 until params.numSMRouterPortsIn) {
    for (vc <- 0 until params.numVCs) {
      for (outVC <- 0 until params.numVCs) {
        va.io.req(i)(vc)(outVC).valid := vaInputValids(i)(vc)
      }
    }
  }

  // VA 轮询指针（每输入端口）
  val vaRrPtr = Seq.fill(params.numSMRouterPortsIn)(RegInit(0.U(log2Ceil(params.numVCs).W)))

  // 将 VA 的授权（多个 inputVC 中的优先项）打包并入队
  for (i <- 0 until params.numSMRouterPortsIn) {
    val anyGrantPerInVC = VecInit.tabulate(params.numVCs) { vc =>
      val grants = VecInit.tabulate(params.numVCs) { outVC => va.io.grant(i)(vc)(outVC).grant }
      grants.asUInt.orR
    }

    val masked = VecInit.tabulate(params.numVCs)(k => anyGrantPerInVC(k) && (k.U >= vaRrPtr(i)))
    val hasMasked = masked.asUInt.orR
    val anyGrant = anyGrantPerInVC.asUInt.orR
    val selInVC = Mux(hasMasked, PriorityEncoder(masked.asUInt), PriorityEncoder(anyGrantPerInVC.asUInt))

    val selOutPerInVC = VecInit.tabulate(params.numVCs) { vc =>
      val grants = VecInit.tabulate(params.numVCs) { outVC => va.io.grant(i)(vc)(outVC).grant }
      PriorityEncoder(grants.asUInt)
    }

    val outPortSel = Mux1H(Seq.tabulate(params.numVCs) { vc => (selInVC === vc.U) -> vaInputPortRegs(i)(vc) })
    val outVCSel = Mux1H(Seq.tabulate(params.numVCs) { vc => (selInVC === vc.U) -> selOutPerInVC(vc) })
    val flitSel = Mux1H(Seq.tabulate(params.numVCs) { vc => (selInVC === vc.U) -> vaInputFlitRegs(i)(vc) })

    vaOutQueues(i).io.enq.bits.flit := flitSel
    vaOutQueues(i).io.enq.bits.outPort := outPortSel
    vaOutQueues(i).io.enq.bits.inVC := selInVC
    vaOutQueues(i).io.enq.bits.outVC := outVCSel
    vaOutQueues(i).io.enq.valid := anyGrant

    when (vaOutQueues(i).io.enq.fire) {
      vaRrPtr(i) := Mux(selInVC === (params.numVCs - 1).U, 0.U, selInVC + 1.U)
    }

    for (vc <- 0 until params.numVCs) {
      vaConsume(i)(vc) := vaOutQueues(i).io.enq.fire && (selInVC === vc.U)
    }
  }

  // 将 RC 输出队列的项 dequeue 到 VA 输入寄存器，并在被 VA 消费前保持。
  for (i <- 0 until params.numSMRouterPortsIn) {
    val headVC = rcOutQueues(i).io.deq.bits.inVC
    val canAcceptHead = Mux1H(Seq.tabulate(params.numVCs) { vc =>
      (headVC === vc.U) -> (!vaInputValids(i)(vc) || vaConsume(i)(vc))
    })
    rcOutQueues(i).io.deq.ready := rcOutQueues(i).io.deq.valid && canAcceptHead

    for (vc <- 0 until params.numVCs) {
      val refill = rcOutQueues(i).io.deq.fire && (headVC === vc.U)
      when (refill) {
        vaInputPortRegs(i)(vc) := rcOutQueues(i).io.deq.bits.outPort
        vaInputVCRegs(i)(vc) := rcOutQueues(i).io.deq.bits.inVC
        vaInputFlitRegs(i)(vc) := rcOutQueues(i).io.deq.bits.flit
        vaInputValids(i)(vc) := true.B
      } .elsewhen (vaConsume(i)(vc)) {
        vaInputValids(i)(vc) := false.B
      }
    }
  }

  // VA -> SA：保存 flit 与选择结果，直到 SA 消费。
  val saConsume = Seq.fill(params.numSMRouterPortsIn)(Seq.fill(params.numVCs)(Wire(Bool())))
  for (i <- 0 until params.numSMRouterPortsIn) {
    val headVC = vaOutQueues(i).io.deq.bits.inVC
    val canAcceptHead = Mux1H(Seq.tabulate(params.numVCs) { vc =>
      (headVC === vc.U) -> (!saInputValids(i)(vc) || saConsume(i)(vc))
    })
    vaOutQueues(i).io.deq.ready := vaOutQueues(i).io.deq.valid && canAcceptHead

    for (vc <- 0 until params.numVCs) {
      val refill = vaOutQueues(i).io.deq.fire && (headVC === vc.U)
      when (refill) {
        saInputPortRegs(i)(vc) := vaOutQueues(i).io.deq.bits.outPort
        saInputVCRegs(i)(vc) := vaOutQueues(i).io.deq.bits.outVC
        saInputFlitRegs(i)(vc) := vaOutQueues(i).io.deq.bits.flit
        saInputValids(i)(vc) := true.B
      } .elsewhen (saConsume(i)(vc)) {
        saInputValids(i)(vc) := false.B
      }
    }
  }

  
  // ===========================================================================
  // 4. SA: Switch Allocation - 开关分配阶段
  // 创建路由器仲裁器
  val arbiter = Module(new RouterArbiter(params, params.numSMRouterPortsIn, params.numSMRouterPortsOut))

  // 连接SA输入
  for (i <- 0 until params.numSMRouterPortsIn) {
    for (vc <- 0 until params.numVCs) {
      for (port <- 0 until params.numSMRouterPortsOut) {
        val req = saInputValids(i)(vc) && (saInputPortRegs(i)(vc) === port.U)
        arbiter.io.req(i)(port)(vc) := req
      }
    }
  }
  
  
  
  // ===========================================================================
  // 5. ST: Switch Traversal - 切换遍历阶段
  // ===========================================================================
  // SA -> ST 队列化：将获得开关授权的 flit 排队传递到 ST
  val saOutQueues = Seq.tabulate(params.numSMRouterPortsIn) { i =>
    Module(new Queue(new SAToSTBundle(params, params.numSMRouterPortsOut), 2))
  }

  // 将 SA 授权的 flit 入队到 saOutQueues
  // SA 轮询指针（每输入端口）
  val saRrPtr = Seq.fill(params.numSMRouterPortsIn)(RegInit(0.U(log2Ceil(params.numVCs).W)))

  for (i <- 0 until params.numSMRouterPortsIn) {
    val grantVec = VecInit.tabulate(params.numVCs) { vc =>
      val perPortMatches = VecInit.tabulate(params.numSMRouterPortsOut) { port =>
        (saInputPortRegs(i)(vc) === port.U) && arbiter.io.gnt(i)(port)(vc)
      }
      perPortMatches.asUInt.orR && saInputValids(i)(vc)
    }

    val masked = VecInit.tabulate(params.numVCs)(k => grantVec(k) && (k.U >= saRrPtr(i)))
    val hasMasked = masked.asUInt.orR
    val anyGrant = grantVec.asUInt.orR
    val selInVC = Mux(hasMasked, PriorityEncoder(masked.asUInt), PriorityEncoder(grantVec.asUInt))

    val selFlit = Mux1H(Seq.tabulate(params.numVCs) { vc => (selInVC === vc.U) -> saInputFlitRegs(i)(vc) })
    val selOutPort = Mux1H(Seq.tabulate(params.numVCs) { vc => (selInVC === vc.U) -> saInputPortRegs(i)(vc) })
    val selOutVC = Mux1H(Seq.tabulate(params.numVCs) { vc => (selInVC === vc.U) -> saInputVCRegs(i)(vc) })

    saOutQueues(i).io.enq.bits.flit := selFlit
    saOutQueues(i).io.enq.bits.outPort := selOutPort
    saOutQueues(i).io.enq.bits.inVC := selInVC
    saOutQueues(i).io.enq.bits.outVC := selOutVC
    saOutQueues(i).io.enq.valid := anyGrant

    when (saOutQueues(i).io.enq.fire) {
      saRrPtr(i) := Mux(selInVC === (params.numVCs - 1).U, 0.U, selInVC + 1.U)
    }

    for (vc <- 0 until params.numVCs) {
      saConsume(i)(vc) := saOutQueues(i).io.enq.fire && (selInVC === vc.U)
    }
  }

  // 创建路由器输出 Mux
  val outputMux = Module(new RouterOutputMux(params, params.numSMRouterPortsIn, params.numSMRouterPortsOut))

  // 连接ST输入到输出Mux：从 saOutQueues dequeue 的项填充到对应的 inputVC channel
  for (i <- 0 until params.numSMRouterPortsIn) {
    val deq = saOutQueues(i).io.deq
    val dqValid = deq.valid
    val dqFlit = deq.bits.flit
    val dqInVC = deq.bits.inVC

    for (vc <- 0 until params.numVCs) {
      // 将 dequeued 的 flit 路由到对应的 in(i)(vc)
      outputMux.io.in(i)(vc).bits := Mux1H(Seq.tabulate(params.numVCs) { k => (dqInVC === k.U) -> dqFlit })
      outputMux.io.in(i)(vc).valid := dqValid && (dqInVC === vc.U)
    }

    // 根据 outputMux 对应通道的 ready 决定是否 dequeue
    val readyForDequeued = Mux1H(Seq.tabulate(params.numVCs) { k => (dqInVC === k.U) -> outputMux.io.in(i)(k).ready })
    saOutQueues(i).io.deq.ready := readyForDequeued
  }
  
  // 连接仲裁器选择信号到输出Mux
  outputMux.io.sel := arbiter.io.sel
  
  // 连接仲裁器的ready信号
  for (port <- 0 until params.numSMRouterPortsOut) {
    for (vc <- 0 until params.numVCs) {
      arbiter.io.ready(port)(vc) := true.B // 简化：假设下游始终就绪
    }
  }
  
  // ==========================================================================
  // ST -> LT 队列化：为每个 output port/VC 插入短队列
  // ==========================================================================
  val stToLtQueues = Seq.tabulate(params.numSMRouterPortsOut) { port =>
    Seq.tabulate(params.numVCs) { vc =>
      Module(new CountingQueue(new Flit(params), 2))
    }
  }

  // 将 outputMux 的 out 端直接入队到 stToLtQueues
  for (port <- 0 until params.numSMRouterPortsOut) {
    for (vc <- 0 until params.numVCs) {
      stToLtQueues(port)(vc).io.enq.bits  := outputMux.io.out(port)(vc).bits
      stToLtQueues(port)(vc).io.enq.valid := outputMux.io.out(port)(vc).valid
      outputMux.io.out(port)(vc).ready    := stToLtQueues(port)(vc).io.enq.ready
    }
  }
  
  // ===========================================================================
  // 6. LT: Link Traversal - 链接遍历阶段
  // ===========================================================================
  // 创建虚拟通道合并模块
  val vcMergers = Seq.fill(params.numSMRouterPortsOut)(Module(new VCMerger(params)))
  
  // 连接输出Mux到虚拟通道合并模块
  for (port <- 0 until params.numSMRouterPortsOut) {
    for (vc <- 0 until params.numVCs) {
      vcMergers(port).io.in(vc).bits     := stToLtQueues(port)(vc).io.deq.bits
      vcMergers(port).io.in(vc).valid    := stToLtQueues(port)(vc).io.deq.valid
      stToLtQueues(port)(vc).io.deq.ready := vcMergers(port).io.in(vc).ready
    }
  }
  
  // 输出寄存器
  val outputRegs = Seq.fill(params.numSMRouterPortsOut)(RegInit(0.U.asTypeOf(new Flit(params))))
  val outputValidRegs = Seq.fill(params.numSMRouterPortsOut)(RegInit(false.B))
  
  // 连接虚拟通道合并模块到输出寄存器
  for (port <- 0 until params.numSMRouterPortsOut) {
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
  
  // ===========================================================================
  // 信用信号处理（输出端口到下游的信用）
  // ===========================================================================
  for (port <- 0 until params.numSMRouterPortsOut) {
    val vcCredits = Wire(Vec(params.numVCs, UInt(params.creditWidth.W)))
    for (vc <- 0 until params.numVCs) {
      val queue   = stToLtQueues(port)(vc)
      val depth   = 2.U
      val free    = depth - queue.io.count
      val maxCred = ((1 << params.creditWidth) - 1).U(params.creditWidth.W)
      val credit  = Mux(free > maxCred, maxCred, free)(params.creditWidth - 1, 0)
      vcCredits(vc) := credit
    }
    io.out(port).creditOut := vcCredits
  }
}

// 伴生对象，提供默认构造函数
object SMRouter {
  def apply(params: NoCParams = DefaultNoCParams): SMRouter = Module(new SMRouter(params))
}
