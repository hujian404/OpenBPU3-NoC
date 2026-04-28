package openbpu

import chisel3._
import chisel3.util._

// 单个输出端口的Mux模块
class OutputMux(params: NoCParams, numInputs: Int) extends Module {
  val io = IO(new Bundle {
    val in = Vec(numInputs, Vec(params.numVCs, Flipped(Decoupled(new Flit(params)))))
    val sel = Input(Vec(params.numVCs, UInt(log2Ceil(numInputs).W)))
    val out = Vec(params.numVCs, Decoupled(new Flit(params)))
  })
  
  for (vc <- 0 until params.numVCs) {
    // 为每个虚拟通道创建一个多路复用器
    val mux = Module(new Mux1H(new Flit(params), numInputs))
    
    // 连接输入
    for (i <- 0 until numInputs) {
      mux.io.in(i) := io.in(i)(vc).bits
      mux.io.sel(i) := (io.sel(vc) === i.U) && io.in(i)(vc).valid
    }
    
    // 连接输出
    io.out(vc).bits := mux.io.out
    io.out(vc).valid := mux.io.sel.asUInt.orR
    
    // 连接ready信号
    for (i <- 0 until numInputs) {
      io.in(i)(vc).ready := (io.sel(vc) === i.U) && io.out(vc).ready
    }
  }
}

// 路由器输出端口Mux阵列
class RouterOutputMux(params: NoCParams, numInputs: Int, numOutputs: Int) extends Module {
  val io = IO(new Bundle {
    val in = Vec(numInputs, Vec(params.numVCs, Flipped(Decoupled(new Flit(params)))))
    val sel = Input(Vec(numOutputs, Vec(params.numVCs, UInt(log2Ceil(numInputs).W))))
    val out = Vec(numOutputs, Vec(params.numVCs, Decoupled(new Flit(params))))
  })

  for (port <- 0 until numOutputs) {
    for (vc <- 0 until params.numVCs) {
      val selected = io.sel(port)(vc)
      io.out(port)(vc).bits := Mux1H(Seq.tabulate(numInputs) { i =>
        (selected === i.U) -> io.in(i)(vc).bits
      })
      io.out(port)(vc).valid := Mux1H(Seq.tabulate(numInputs) { i =>
        (selected === i.U) -> io.in(i)(vc).valid
      })
    }
  }

  for (i <- 0 until numInputs) {
    for (vc <- 0 until params.numVCs) {
      io.in(i)(vc).ready := VecInit.tabulate(numOutputs) { port =>
        (io.sel(port)(vc) === i.U) && io.out(port)(vc).ready
      }.asUInt.orR
    }
  }
}

// 虚拟通道合并模块，将多个VC的输出合并到一个端口
class VCMerger(params: NoCParams) extends Module {
  val io = IO(new Bundle {
    val in = Vec(params.numVCs, Flipped(Decoupled(new Flit(params))))
    val out = Decoupled(new Flit(params))
  })
  
  // VC优先级仲裁（可以根据需要调整优先级策略）
  val arbiter = Module(new RoundRobinArbiter(params.numVCs))
  
  // 连接仲裁器
  arbiter.io.req := VecInit.tabulate(params.numVCs)(vc => io.in(vc).valid)
  arbiter.io.ready := io.out.ready
  
  // 多路复用器
  val mux = Module(new Mux1H(new Flit(params), params.numVCs))
  
  // 连接输入到多路复用器
  for (vc <- 0 until params.numVCs) {
    mux.io.in(vc) := io.in(vc).bits
    mux.io.sel(vc) := arbiter.io.gnt(vc)
  }
  
  // 连接输出
  io.out.bits := mux.io.out
  io.out.valid := arbiter.io.valid
  
  // 连接ready信号
  for (vc <- 0 until params.numVCs) {
    io.in(vc).ready := arbiter.io.gnt(vc)
  }

  // 断言：每个周期 grant onehot0，且 valid 与任一输入 valid 对齐
  when (io.out.valid) {
    assert(PopCount(arbiter.io.gnt) <= 1.U, "VCMerger: multiple grants for single-cycle output")
  }
}
