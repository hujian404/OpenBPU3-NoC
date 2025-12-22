package openbpu

import chisel3._
import chisel3.util._

// 路由计算输入现在使用 Decoupled 接口，便于插入 Queue/pipe
// 路由计算输出接口
class RCOutputInterface(params: NoCParams, numOutputs: Int) extends Bundle {
  val port = Output(UInt(log2Ceil(numOutputs).W))
  val vc = Output(UInt(log2Ceil(params.numVCs).W))
  val ready = Input(Bool())
}

// 通用路由计算模块
class RoutingUnit(params: NoCParams, val numInputs: Int, val numOutputs: Int) extends Module {
  val io = IO(new Bundle {
    // 每个输入端口为 Decoupled Flit（Flipped 表示路由单元作为接收端）
    val in = Vec(numInputs, Flipped(Decoupled(new Flit(params))))
    val out = Vec(numInputs, new RCOutputInterface(params, numOutputs))
  })
  
  // 默认实现，子类将重写此逻辑
  for (i <- 0 until numInputs) {
    io.out(i).port := 0.U
    io.out(i).vc := io.in(i).bits.vc
    // 默认总是准备好接收输入，具体实现可覆盖以实现更精细的握手
    io.in(i).ready := true.B
  }
}

// SM-Router路由计算模块
class SMRouterRC(params: NoCParams) extends RoutingUnit(params, params.numSMRouterPortsIn, params.numSMRouterPortsOut) {
  // SM-Router路由逻辑：基于目标地址映射到对应的MC-Router
  for (i <- 0 until numInputs) {
    // 使用 Flit 的 destId 字段的低位映射到 MC-Router
    val dest = io.in(i).bits.destId
    val portWidth = log2Ceil(numOutputs)
    val mapped = if (numOutputs == 1) 0.U else dest(portWidth-1, 0)
    io.out(i).port := mapped
    io.out(i).vc := io.in(i).bits.vc
  }
}

// MC-Router路由计算模块
class MCRouterRC(params: NoCParams) extends RoutingUnit(params, params.numMCRouterPortsIn, params.numMCRouterPortsOut) {
  // MC-Router路由逻辑：基于目标地址映射到对应的L2切片
  for (i <- 0 until numInputs) {
    // 使用 Flit 的 destId 字段的低位直接选择 L2 切片（更适合GPU映射场景）
    val dest = io.in(i).bits.destId
    val portWidth = log2Ceil(numOutputs)
    val mapped = if (numOutputs == 1) 0.U else dest(portWidth-1, 0)
    io.out(i).port := mapped
    io.out(i).vc := io.in(i).bits.vc
  }
}

// 路由请求接口
class RouteRequest(params: NoCParams) extends Bundle {
  val fromPort = UInt(log2Ceil(params.numSMRouterPortsIn.max(params.numMCRouterPortsIn)).W)
  val toPort = UInt(log2Ceil(params.numSMRouterPortsOut.max(params.numMCRouterPortsOut)).W)
  val vc = UInt(log2Ceil(params.numVCs).W)
  val valid = Bool()
}
