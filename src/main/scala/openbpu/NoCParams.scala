package openbpu

import chisel3._

case class NoCParams(
  // 拓扑参数
  numSMClusters: Int = 8,        // SM集群数量 = SM-Router数量
  numMCs: Int = 8,               // 内存控制器数量 = MC-Router数量
  numSMsPerCluster: Int = 10,    // 每个集群的SM数量
  numL2SlicesPerMC: Int = 8,     // 每个MC的L2切片数量
  
  // 路由器参数
  flitWidth: Int = 64,           // Flit位宽
  bufferDepth: Int = 16,         // 输入缓冲深度
  numVCs: Int = 2,               // 虚拟通道数量
  
  // 地址映射参数
  addrWidth: Int = 64,           // 地址宽度
  pageSize: Int = 4096,          // 页大小
  
  // 流量控制参数
  creditWidth: Int = 5           // 信用信号宽度
) {
  // 派生参数
  val numSMs: Int = numSMClusters * numSMsPerCluster
  val numL2Slices: Int = numMCs * numL2SlicesPerMC
  val numSMRouterPortsIn: Int = numSMsPerCluster
  val numSMRouterPortsOut: Int = numMCs
  val numMCRouterPortsIn: Int = numSMClusters
  val numMCRouterPortsOut: Int = numL2SlicesPerMC
}

// 默认参数配置
object DefaultNoCParams extends NoCParams()
