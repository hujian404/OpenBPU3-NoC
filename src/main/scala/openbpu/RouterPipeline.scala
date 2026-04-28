package openbpu

import chisel3._
import chisel3.util._

// RC -> VA 传递的数据结构：携带路由结果以及输入 VC 信息
class RCToVABundle(params: NoCParams, numOutputs: Int) extends Bundle {
  val flit    = new Flit(params)
  val outPort = UInt(log2Ceil(numOutputs).W)
  val inVC    = UInt(log2Ceil(params.numVCs).W)
}

// VA -> SA 传递的数据结构：记录为某个输入 VC 选中的输出端口和输出 VC
class VAToSABundle(params: NoCParams, numOutputs: Int) extends Bundle {
  val flit    = new Flit(params)
  val outPort = UInt(log2Ceil(numOutputs).W)
  val inVC    = UInt(log2Ceil(params.numVCs).W)
  val outVC   = UInt(log2Ceil(params.numVCs).W)
}

// SA -> ST 传递的数据结构：带有最终要发送的 flit 以及端口/VC 选择信息
class SAToSTBundle(params: NoCParams, numOutputs: Int) extends Bundle {
  val flit    = new Flit(params)
  val outPort = UInt(log2Ceil(numOutputs).W)
  val inVC    = UInt(log2Ceil(params.numVCs).W)
  val outVC   = UInt(log2Ceil(params.numVCs).W)
}

