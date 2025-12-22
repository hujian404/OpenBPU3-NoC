package openbpu

import chisel3._
import chisel3.util._

// Flit类型定义
trait FlitType {
  val DATA = 0.U(2.W)
  val REQUEST = 1.U(2.W)
  val RESPONSE = 2.U(2.W)
  val CONTROL = 3.U(2.W)
}

// Flit格式定义
class Flit(params: NoCParams) extends Bundle {
  // Flit头部信息 (16 bits)
  val flitType = UInt(2.W)            // Flit类型
  val isLast = Bool()                  // 是否为包的最后一个Flit
  val vc = UInt(log2Ceil(params.numVCs).W)  // 虚拟通道ID
  val destId = UInt(log2Ceil(params.numSMClusters.max(params.numL2Slices)).W)  // 目标ID
  
  // 数据负载 (48 bits)
  val data = UInt(48.W)
  
  // 总位宽：2 + 1 + log2(numVCs) + log2(max(destId)) + 48 = 64 bits
  
  // 辅助方法
  def isRequest: Bool = flitType === FlitType.REQUEST
  def isResponse: Bool = flitType === FlitType.RESPONSE
  def isData: Bool = flitType === FlitType.DATA
  def isControl: Bool = flitType === FlitType.CONTROL
}

// NoC接口Bundle，包含Decoupled接口和信用信号
class NoCInterface(params: NoCParams) extends Bundle {
  // Flit传输接口（Decoupled）
  val flit = Decoupled(new Flit(params))
  
  // 信用信号（用于流量控制）
  val creditIn = Input(UInt(params.creditWidth.W))   // 从下游接收的信用
  val creditOut = Output(UInt(params.creditWidth.W)) // 向上游发送的信用
  
  // 辅助构造函数
  def this() = this(DefaultNoCParams)
}

// 路由器端口接口
class RouterPort(params: NoCParams) extends Bundle {
  val in = Flipped(new NoCInterface(params))  // 输入端口
  val out = new NoCInterface(params)          // 输出端口
  
  def this() = this(DefaultNoCParams)
}

// 伴生对象，提供常量和辅助方法
object FlitType extends FlitType
