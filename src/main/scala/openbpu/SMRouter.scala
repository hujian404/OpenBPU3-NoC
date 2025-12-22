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
    inputBuffers(i).io.creditIn := VecInit.fill(params.numVCs)(io.in(i).creditOut)
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
  
  // 为输入缓冲器输出提供ready信号
  for (i <- 0 until params.numSMRouterPortsIn) {
    for (vc <- 0 until params.numVCs) {
      // 简化：假设流水线始终准备好接收
      inputBuffers(i).io.out(vc).ready := true.B
    }
  }
  
  // 连接BW输出到RC输入寄存器
  for (i <- 0 until params.numSMRouterPortsIn) {
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
  val rc = Module(new SMRouterRC(params))
  
  // 连接RC输入
  for (i <- 0 until params.numSMRouterPortsIn) {
    // 选择一个有效VC发送到RC
    val vcValids = VecInit.tabulate(params.numVCs)(vc => rcInputValids(i)(vc))
    val hasValidFlit = vcValids.asUInt.orR
    val selectedVC = Mux(hasValidFlit, PriorityEncoder(vcValids.asUInt), 0.U)
    
    // 使用Mux1H选择正确的Flit，因为Chisel不允许UInt作为数组索引
    val selectedFlit = Mux1H(Seq.tabulate(params.numVCs) { vc =>
      (selectedVC === vc.U) -> rcInputFlits(i)(vc)
    })
    
    rc.io.in(i).flit := selectedFlit
    rc.io.in(i).valid := hasValidFlit
    rc.io.out(i).ready := true.B // 简化：假设RC输出始终就绪
  }
  
  // RC阶段输出：每个输入端口的输出端口号和VC号
  val rcOutPorts = RegNext(VecInit.tabulate(params.numSMRouterPortsIn)(i => rc.io.out(i).port))
  val rcOutVCs = RegNext(VecInit.tabulate(params.numSMRouterPortsIn)(i => rc.io.out(i).vc))
  val rcOutValids = RegNext(VecInit.tabulate(params.numSMRouterPortsIn)(i => rc.io.in(i).valid))
  
  // ===========================================================================
  // 流水线寄存器：RC → VA
  // ===========================================================================
  val vaInputPortRegs = Seq.fill(params.numSMRouterPortsIn)(Seq.fill(params.numVCs)(
    RegInit(0.U(log2Ceil(params.numSMRouterPortsOut).W))
  ))
  val vaInputVCRegs = Seq.fill(params.numSMRouterPortsIn)(Seq.fill(params.numVCs)(
    RegInit(0.U(log2Ceil(params.numVCs).W))
  ))
  val vaInputValids = Seq.fill(params.numSMRouterPortsIn)(Seq.fill(params.numVCs)(RegInit(false.B)))
  
  // 连接RC输出到VA输入寄存器
  for (i <- 0 until params.numSMRouterPortsIn) {
    for (vc <- 0 until params.numVCs) {
      when (rcOutValids(i) && (rcOutVCs(i) === vc.U)) {
        vaInputPortRegs(i)(vc) := rcOutPorts(i)
        vaInputVCRegs(i)(vc) := rcOutVCs(i)
        vaInputValids(i)(vc) := true.B
      } .otherwise {
        vaInputValids(i)(vc) := false.B
      }
    }
  }
  
  // ===========================================================================
  // 3. VA: VC Allocation - 虚拟通道分配阶段
  // ===========================================================================
  // 创建VC分配模块
  val va = Module(new VCAllocator(params, params.numSMRouterPortsIn))
  
  // 连接VA输入
  for (i <- 0 until params.numSMRouterPortsIn) {
    for (vc <- 0 until params.numVCs) {
      // 为每个输入VC请求所有可能的输出VC
      for (outVC <- 0 until params.numVCs) {
        va.io.req(i)(vc)(outVC).valid := vaInputValids(i)(vc) && (vaInputVCRegs(i)(vc) === outVC.U)
      }
    }
  }
  
  // VA阶段输出：VC分配授权
  val vaOutGrants = Seq.tabulate(params.numSMRouterPortsIn) { i =>
    Seq.tabulate(params.numVCs) { vc =>
      Seq.tabulate(params.numVCs) { outVC =>
        RegNext(va.io.grant(i)(vc)(outVC).grant)
      }
    }
  }
  
  // ===========================================================================
  // 流水线寄存器：VA → SA
  // ===========================================================================
  val saInputPortRegs = Seq.fill(params.numSMRouterPortsIn)(Seq.fill(params.numVCs)(
    RegInit(0.U(log2Ceil(params.numSMRouterPortsOut).W))
  ))
  val saInputVCRegs = Seq.fill(params.numSMRouterPortsIn)(Seq.fill(params.numVCs)(
    RegInit(0.U(log2Ceil(params.numVCs).W))
  ))
  val saInputValids = Seq.fill(params.numSMRouterPortsIn)(Seq.fill(params.numVCs)(RegInit(false.B)))
  
  // 连接VA输出到SA输入寄存器
  for (i <- 0 until params.numSMRouterPortsIn) {
    for (vc <- 0 until params.numVCs) {
      // 检查是否获得VC授权
      val vcGranted = vaOutGrants(i)(vc)(vc)
      
      when (vcGranted) {
        saInputPortRegs(i)(vc) := vaInputPortRegs(i)(vc)
        saInputVCRegs(i)(vc) := vaInputVCRegs(i)(vc)
        saInputValids(i)(vc) := true.B
      } .otherwise {
        saInputValids(i)(vc) := false.B
      }
    }
  }
  
  // ===========================================================================
  // 4. SA: Switch Allocation - 开关分配阶段
  // ===========================================================================
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
  // 流水线寄存器：SA → ST
  // ===========================================================================
  // ST阶段需要的Flit数据和授权信号
  val stInputFlits = Seq.tabulate(params.numSMRouterPortsIn) { i =>
    Seq.tabulate(params.numVCs) { vc =>
      RegNext(rcInputFlits(i)(vc))
    }
  }
  val stInputValids = Seq.fill(params.numSMRouterPortsIn)(Seq.fill(params.numVCs)(RegInit(false.B)))
  
  // 连接SA输出到ST输入
  for (i <- 0 until params.numSMRouterPortsIn) {
    for (vc <- 0 until params.numVCs) {
      // 检查是否获得开关授权
      val port = saInputPortRegs(i)(vc)
      val saGranted = arbiter.io.gnt(i)(port)(vc)
      
      stInputValids(i)(vc) := saInputValids(i)(vc) && saGranted
    }
  }
  
  // ===========================================================================
  // 5. ST: Switch Traversal - 切换遍历阶段
  // ===========================================================================
  // 创建路由器输出Mux
  val outputMux = Module(new RouterOutputMux(params, params.numSMRouterPortsIn, params.numSMRouterPortsOut))
  
  // 连接ST输入到输出Mux
  for (i <- 0 until params.numSMRouterPortsIn) {
    for (vc <- 0 until params.numVCs) {
      outputMux.io.in(i)(vc).bits := stInputFlits(i)(vc)
      outputMux.io.in(i)(vc).valid := stInputValids(i)(vc)
      // ready信号由RouterOutputMux内部驱动，不需要外部赋值
    }
  }
  
  // 连接仲裁器选择信号到输出Mux
  outputMux.io.sel := arbiter.io.sel
  
  // 连接仲裁器的ready信号
  for (port <- 0 until params.numSMRouterPortsOut) {
    for (vc <- 0 until params.numVCs) {
      arbiter.io.ready(port)(vc) := true.B // 简化：假设下游始终就绪
    }
  }
  
  // ===========================================================================
  // 流水线寄存器：ST → LT
  // ===========================================================================
  val ltInputFlits = Seq.tabulate(params.numSMRouterPortsOut) {
    port => Seq.tabulate(params.numVCs) {
      vc => RegNext(outputMux.io.out(port)(vc).bits)
    }
  }
  val ltInputValids = Seq.tabulate(params.numSMRouterPortsOut) {
    port => Seq.tabulate(params.numVCs) {
      vc => RegNext(outputMux.io.out(port)(vc).valid)
    }
  }
  val ltInputReadys = Seq.tabulate(params.numSMRouterPortsOut) {
    port => Seq.tabulate(params.numVCs) {
      vc => Wire(Bool())
    }
  }
  // 连接ready信号的流水线寄存器
  val ltInputReadyRegs = Seq.tabulate(params.numSMRouterPortsOut) {
    port => Seq.tabulate(params.numVCs) {
      vc => RegNext(ltInputReadys(port)(vc))
    }
  }
  // 将ready信号反馈到outputMux
  for (port <- 0 until params.numSMRouterPortsOut) {
    for (vc <- 0 until params.numVCs) {
      outputMux.io.out(port)(vc).ready := ltInputReadyRegs(port)(vc)
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
      vcMergers(port).io.in(vc).bits := ltInputFlits(port)(vc)
      vcMergers(port).io.in(vc).valid := ltInputValids(port)(vc)
      ltInputReadys(port)(vc) := vcMergers(port).io.in(vc).ready
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
  
  // 信用信号处理（输出端口到下游的信用）
  for (port <- 0 until params.numSMRouterPortsOut) {
    // 简化：使用固定信用值
    io.out(port).creditOut := params.bufferDepth.U
  }
}

// 伴生对象，提供默认构造函数
object SMRouter {
  def apply(params: NoCParams = DefaultNoCParams): SMRouter = Module(new SMRouter(params))
}
