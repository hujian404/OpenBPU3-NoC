package openbpu

import chisel3._
import chisel3.util._
import chisel3.stage._

// 顶层NoC模块
class OpenBPUNoC(params: NoCParams) extends Module {
  val io = IO(new Bundle {
    // SM接口（连接到SM）
    val sm = Vec(params.numSMs, Flipped(new NoCInterface(params)))
    
    // L2切片接口（连接到L2缓存切片）
    val l2 = Vec(params.numL2Slices, new NoCInterface(params))
  })
  
  // 1. SM-Router实例阵列
  val smRouters = Seq.fill(params.numSMClusters)(Module(new SMRouter(params)))
  
  // 2. MC-Router实例阵列
  val mcRouters = Seq.fill(params.numMCs)(Module(new MCRouter(params)))
  
  // 3. 连接SM到SM-Router
  for (cluster <- 0 until params.numSMClusters) {
    for (smInCluster <- 0 until params.numSMsPerCluster) {
      val smId = cluster * params.numSMsPerCluster + smInCluster
      smRouters(cluster).io.in(smInCluster) <> io.sm(smId)
    }
  }
  
  // 4. 连接SM-Router到MC-Router（全互连）
  for (smRouterId <- 0 until params.numSMClusters) {
    for (mcRouterId <- 0 until params.numMCs) {
      // SM-Router的输出端口连接到MC-Router的输入端口
      mcRouters(mcRouterId).io.in(smRouterId) <> smRouters(smRouterId).io.out(mcRouterId)
    }
  }
  
  // 5. 连接MC-Router到L2切片
  for (mcRouterId <- 0 until params.numMCs) {
    for (l2InMC <- 0 until params.numL2SlicesPerMC) {
      val l2Id = mcRouterId * params.numL2SlicesPerMC + l2InMC
      io.l2(l2Id) <> mcRouters(mcRouterId).io.out(l2InMC)
    }
  }
}

// 伴生对象，提供默认构造函数
object OpenBPUNoC {
  def apply(params: NoCParams = DefaultNoCParams): OpenBPUNoC = Module(new OpenBPUNoC(params))
}

// 顶层测试模块生成器
object OpenBPUNoCGenerator extends App {
  val params = NoCParams(
    numSMClusters = 8,
    numMCs = 8,
    numSMsPerCluster = 10,
    numL2SlicesPerMC = 8,
    flitWidth = 64,
    bufferDepth = 16,
    numVCs = 2
  )
  
  println(s"Generating OpenBPU NoC with parameters:")
  println(s"- SM clusters: ${params.numSMClusters}")
  println(s"- MC routers: ${params.numMCs}")
  println(s"- SMs per cluster: ${params.numSMsPerCluster}")
  println(s"- L2 slices per MC: ${params.numL2SlicesPerMC}")
  println(s"- Total SMs: ${params.numSMs}")
  println(s"- Total L2 slices: ${params.numL2Slices}")
  
  // 生成Verilog代码
  import _root_.circt.stage.ChiselStage
  ChiselStage.emitSystemVerilogFile(new OpenBPUNoC(params), args.toArray)
}
