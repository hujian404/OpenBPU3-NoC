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
  // Flit头部信息
  val flitType = UInt(2.W)            // Flit类型
  val isLast = Bool()                 // 是否为包的最后一个Flit
  val vc = UInt(params.vcWidth.W)     // 虚拟通道ID
  // destId 编码全局目的节点号。当前请求网络使用 L2 slice 全局编号做路由。
  val destId = UInt(params.destWidth.W)
  // 数据负载宽度统一由 NoCParams 推导，避免 RTL / wrapper / build 脚本漂移。
  val data = UInt(params.flitDataWidth.W)
  
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
  // 为每个虚拟通道维护独立的信用信号
  val creditIn = Input(Vec(params.numVCs, UInt(params.creditWidth.W)))   // 从下游接收的每个VC的信用
  val creditOut = Output(Vec(params.numVCs, UInt(params.creditWidth.W))) // 向上游发送的每个VC的信用
  
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
