package openbpu

import chisel3._
import chisel3.util._

// 单个输出端口的仲裁器接口
class ArbiterInterface(params: NoCParams, numInputs: Int) extends Bundle {
  val req = Input(Vec(numInputs, Bool()))
  val gnt = Output(Vec(numInputs, Bool()))
  val sel = Output(UInt(log2Ceil(numInputs).W))
  val valid = Output(Bool())
  val ready = Input(Bool())
}

// Round-Robin仲裁器
class RoundRobinArbiter(numInputs: Int) extends Module {
  val io = IO(new Bundle {
    val req = Input(Vec(numInputs, Bool()))
    val gnt = Output(Vec(numInputs, Bool()))
    val sel = Output(UInt(log2Ceil(numInputs).W))
    val valid = Output(Bool())
    val ready = Input(Bool())
  })
  
  // 当前优先级指针
  val ptr = RegInit(0.U(log2Ceil(numInputs).W))
  
  // 计算请求掩码
  val mask = VecInit.tabulate(numInputs) { i =>
    i.U >= ptr
  }
  
  // 带掩码的请求
  val maskedReq = VecInit.tabulate(numInputs) { i =>
    io.req(i) && mask(i)
  }
  
  // 选择最高优先级的请求
  val selOH = VecInit.tabulate(numInputs) { i =>
    PriorityEncoderOH(maskedReq.asUInt)
  }
  
  // 如果掩码请求为空，使用未掩码请求
  val finalReq = Mux(maskedReq.asUInt.orR, maskedReq.asUInt, io.req.asUInt)
  val finalSelOH = PriorityEncoderOH(finalReq)
  
  // 选择信号
  val sel = PriorityEncoder(finalReq)
  
  // 授权信号
  val gnt = VecInit.tabulate(numInputs) { i =>
    finalSelOH(i) && io.ready
  }
  
  // 更新优先级指针
  when (io.valid && io.ready) {
    ptr := Mux(sel === (numInputs - 1).U, 0.U, sel + 1.U)
  }
  
  // 输出赋值
  io.gnt := gnt
  io.sel := sel
  io.valid := io.req.asUInt.orR
}

// 按虚拟通道分组的仲裁器
class VCArbiter(params: NoCParams, numInputs: Int) extends Module {
  val io = IO(new Bundle {
    val req = Input(Vec(numInputs, Vec(params.numVCs, Bool())))
    val gnt = Output(Vec(numInputs, Vec(params.numVCs, Bool())))
    val sel = Output(Vec(params.numVCs, UInt(log2Ceil(numInputs).W)))
    val valid = Output(Vec(params.numVCs, Bool()))
    val ready = Input(Vec(params.numVCs, Bool()))
  })
  
  // 为每个虚拟通道创建一个仲裁器
  val arbiters = Seq.fill(params.numVCs)(Module(new RoundRobinArbiter(numInputs)))
  
  for (vc <- 0 until params.numVCs) {
    // 收集该VC的所有请求
    val vcReq = VecInit.tabulate(numInputs) { i =>
      io.req(i)(vc)
    }
    
    // 连接仲裁器
    arbiters(vc).io.req := vcReq
    arbiters(vc).io.ready := io.ready(vc)
    
    // 分发授权信号
    for (i <- 0 until numInputs) {
      io.gnt(i)(vc) := arbiters(vc).io.gnt(i)
    }
    
    // 输出选择信号和有效信号
    io.sel(vc) := arbiters(vc).io.sel
    io.valid(vc) := arbiters(vc).io.valid

    // 断言：每个 VC 上下游的授权 onehot0，且 sel 与 gnt 一致
    when (io.valid(vc)) {
      assert(PopCount(io.gnt.map(_(vc))) <= 1.U, "VCArbiter: multiple grants for same VC")
      for (i <- 0 until numInputs) {
        when (io.gnt(i)(vc)) {
          assert(io.sel(vc) === i.U, "VCArbiter: sel does not match grant")
        }
      }
    }
  }
}

// 路由器端口仲裁器阵列
class RouterArbiter(params: NoCParams, numInputs: Int, numOutputs: Int) extends Module {
  val io = IO(new Bundle {
    val req = Input(Vec(numInputs, Vec(numOutputs, Vec(params.numVCs, Bool()))))
    val gnt = Output(Vec(numInputs, Vec(numOutputs, Vec(params.numVCs, Bool()))))
    val sel = Output(Vec(numOutputs, Vec(params.numVCs, UInt(log2Ceil(numInputs).W))))
    val valid = Output(Vec(numOutputs, Vec(params.numVCs, Bool())))
    val ready = Input(Vec(numOutputs, Vec(params.numVCs, Bool())))
  })
  
  // 为每个输出端口创建一个VC仲裁器
  val portArbiters = Seq.fill(numOutputs)(Module(new VCArbiter(params, numInputs)))
  
  for (port <- 0 until numOutputs) {
    // 收集所有输入到该输出端口的请求
    val portReq = VecInit.tabulate(numInputs) { i =>
      VecInit.tabulate(params.numVCs) { vc =>
        io.req(i)(port)(vc)
      }
    }
    
    // 连接仲裁器
    portArbiters(port).io.req := portReq
    portArbiters(port).io.ready := io.ready(port)
    
    // 分发授权信号
    for (i <- 0 until numInputs) {
      io.gnt(i)(port) := portArbiters(port).io.gnt(i)
    }
    
    // 输出选择信号和有效信号
    io.sel(port) := portArbiters(port).io.sel
    io.valid(port) := portArbiters(port).io.valid

    // 断言：每个 output port / VC 上的 gnt onehot0
    for (vc <- 0 until params.numVCs) {
      val gVec = VecInit.tabulate(numInputs)(i => io.gnt(i)(port)(vc))
      when (io.valid(port)(vc)) {
        assert(PopCount(gVec) <= 1.U, "RouterArbiter: multiple grants for same output/VC")
      }
    }
  }
}
