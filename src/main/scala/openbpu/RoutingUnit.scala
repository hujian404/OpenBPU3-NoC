package openbpu

import chisel3._
import chisel3.util._

// 路由计算输入接口
class RCInputInterface(params: NoCParams) extends Bundle {
  val flit = Input(new Flit(params))
  val valid = Input(Bool())
}

// 路由计算输出接口
class RCOutputInterface(params: NoCParams, numOutputs: Int) extends Bundle {
  val port = Output(UInt(log2Ceil(numOutputs).W))
  val vc = Output(UInt(log2Ceil(params.numVCs).W))
  val ready = Input(Bool())
}

// 通用路由计算模块
class RoutingUnit(params: NoCParams, val numInputs: Int, val numOutputs: Int) extends Module {
  val io = IO(new Bundle {
    val in = Vec(numInputs, new RCInputInterface(params))
    val out = Vec(numInputs, new RCOutputInterface(params, numOutputs))
  })
  
  // 默认实现，子类将重写此逻辑
  for (i <- 0 until numInputs) {
    io.out(i).port := 0.U
    io.out(i).vc := io.in(i).flit.vc
  }
}

// SM-Router路由计算模块
class SMRouterRC(params: NoCParams) extends RoutingUnit(params, params.numSMRouterPortsIn, params.numSMRouterPortsOut) {
  // SM-Router路由逻辑：基于目标地址映射到对应的MC-Router
  for (i <- 0 until numInputs) {
    // 简单的地址哈希映射（实际应用中应基于地址映射表）
    val addr = io.in(i).flit.data
    val mcId = addr(31, 28) // 使用数据的高4位作为MC-Router ID
    
    // 确保mcId在有效范围内
    val validMcId = mcId(log2Ceil(numOutputs)-1, 0)
    
    // 输出端口 = MC-Router ID
    io.out(i).port := validMcId
    io.out(i).vc := io.in(i).flit.vc
  }
}

// MC-Router路由计算模块
class MCRouterRC(params: NoCParams) extends RoutingUnit(params, params.numMCRouterPortsIn, params.numMCRouterPortsOut) {
  // MC-Router路由逻辑：基于目标地址映射到对应的L2切片
  for (i <- 0 until numInputs) {
    // 简单的地址哈希映射（实际应用中应基于地址映射表）
    val addr = io.in(i).flit.data
    
    // 计算L2切片ID的位宽
    val l2SliceIdWidth = log2Ceil(numOutputs)
    
    // 安全的位提取：提取地址中的相应位作为L2切片ID
    val lo = log2Ceil(params.pageSize)
    val hi = lo + l2SliceIdWidth - 1
    
    // 处理numOutputs=1的特殊情况，此时位宽为0
    val l2SliceId = if (numOutputs == 1) {
      0.U // 如果只有一个输出端口，直接映射到0
    } else {
      addr(hi, lo)
    }
    
    // 输出端口 = L2切片ID
    io.out(i).port := l2SliceId
    io.out(i).vc := io.in(i).flit.vc
  }
}

// 路由请求接口
class RouteRequest(params: NoCParams) extends Bundle {
  val fromPort = UInt(log2Ceil(params.numSMRouterPortsIn.max(params.numMCRouterPortsIn)).W)
  val toPort = UInt(log2Ceil(params.numSMRouterPortsOut.max(params.numMCRouterPortsOut)).W)
  val vc = UInt(log2Ceil(params.numVCs).W)
  val valid = Bool()
}
