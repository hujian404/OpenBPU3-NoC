package openbpu

import chisel3._
import chisel3.util._

// VC分配请求
class VCAllocationRequest(params: NoCParams) extends Bundle {
  val valid = Bool()
}

// VC分配授权
class VCAllocationGrant(params: NoCParams) extends Bundle {
  val vc = UInt(log2Ceil(params.numVCs).W)
  val grant = Bool()
}

// VC分配模块，输入VC仲裁输出VC
class VCAllocator(params: NoCParams, numInputs: Int) extends Module {
  val io = IO(new Bundle {
    // 输入请求：每个输入端口的每个VC请求一个输出VC
    val req = Input(Vec(numInputs, Vec(params.numVCs, Vec(params.numVCs, new VCAllocationRequest(params)))))
    // 输出授权：每个输入端口的每个VC是否获得输出VC授权
    val grant = Output(Vec(numInputs, Vec(params.numVCs, Vec(params.numVCs, new VCAllocationGrant(params)))))
  })
  
  // 为每个输出VC创建一个仲裁器
  for (outputVC <- 0 until params.numVCs) {
    // 收集所有请求该输出VC的输入VC
    val vcArbiter = Module(new RoundRobinArbiter(numInputs * params.numVCs))
    
    // 构建请求向量
    val requests = VecInit.tabulate(numInputs * params.numVCs) {
      idx => {
        val inputPort = idx / params.numVCs
        val inputVC = idx % params.numVCs
        io.req(inputPort)(inputVC)(outputVC).valid
      }
    }
    
    // 连接仲裁器
    vcArbiter.io.req := requests
    vcArbiter.io.ready := true.B // 始终允许分配
    
    // 分发授权
    val grants = VecInit.tabulate(numInputs * params.numVCs) {
      idx => vcArbiter.io.gnt(idx)
    }
    
    // 映射回输入端口和输入VC
    for (inputPort <- 0 until numInputs) {
      for (inputVC <- 0 until params.numVCs) {
        val idx = inputPort * params.numVCs + inputVC
        io.grant(inputPort)(inputVC)(outputVC).grant := grants(idx)
        io.grant(inputPort)(inputVC)(outputVC).vc := outputVC.U
      }
    }
  }
}
