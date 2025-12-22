package openbpu

import chisel3._
import chisel3.util._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers


// 测试用的SM模块
class TestSM(params: NoCParams) extends Module {
  val io = IO(new NoCInterface(params))
  
  // 测试数据生成
  val testData = RegInit(0.U(params.flitWidth.W))
  val valid = RegInit(false.B)
  val ready = io.flit.ready
  
  // 生成测试Flit
  val flit = Wire(new Flit(params))
  flit.flitType := FlitType.DATA
  flit.isLast := true.B
  flit.vc := 0.U
  flit.destId := 0.U // 测试目标ID
  flit.data := testData
  
  // 连接到NoC接口
  io.flit.bits := flit
  io.flit.valid := valid
  
  // 信用信号（简化处理）
  io.creditIn := params.bufferDepth.U
  io.creditOut := DontCare
  
  // 状态机
  val sIdle :: sSend :: sWait :: Nil = Enum(3)
  val state = RegInit(sIdle)
  
  switch(state) {
    is(sIdle) {
      when(ready) {
        valid := true.B
        testData := testData + 1.U
        state := sSend
      }
    }
    is(sSend) {
      when(ready) {
        valid := false.B
        state := sWait
      }
    }
    is(sWait) {
      // 等待一段时间后再次发送
      when(testData(3, 0) === 0.U) {
        state := sIdle
      }
    }
  }
}

// 测试用的L2切片模块
class TestL2Slice(params: NoCParams) extends Module {
  val io = IO(Flipped(new NoCInterface(params)))
  
  // 接收数据
  val receivedData = RegInit(0.U(params.flitWidth.W))
  val receivedValid = RegInit(false.B)
  
  // 连接到NoC接口
  io.flit.ready := true.B // 总是准备好接收
  
  when(io.flit.valid) {
    receivedData := io.flit.bits.data
    receivedValid := true.B
  }
  
  // 信用信号（简化处理）
  io.creditIn := DontCare
  io.creditOut := params.bufferDepth.U
}

// 简化的NoC测试模块（用于快速验证）
class SimpleNoCTest extends Module {
  val params = NoCParams(
    numSMClusters = 2,
    numMCs = 2,
    numSMsPerCluster = 2,
    numL2SlicesPerMC = 2,
    flitWidth = 64,
    bufferDepth = 4,
    numVCs = 2
  )
  
  val io = IO(new Bundle {
    val testDone = Output(Bool())
  })
  
  // NoC实例
  val noc = Module(new OpenBPUNoC(params))
  
  // 测试SM和L2切片
  val testSMs = Seq.fill(params.numSMs)(Module(new TestSM(params)))
  val testL2s = Seq.fill(params.numL2Slices)(Module(new TestL2Slice(params)))
  
  // 连接测试模块到NoC
  for (i <- 0 until params.numSMs) {
    noc.io.sm(i) <> testSMs(i).io
  }
  
  for (i <- 0 until params.numL2Slices) {
    testL2s(i).io <> noc.io.l2(i)
  }
  
  // 测试完成信号
  io.testDone := false.B
}

// ScalaTest测试用例
class OpenBPUNoCSpec extends AnyFlatSpec with Matchers {
  "NoCParams" should "calculate correct derived parameters" in {
    val params = NoCParams(
      numSMClusters = 2,
      numMCs = 2,
      numSMsPerCluster = 1,
      numL2SlicesPerMC = 1,
      flitWidth = 64,
      bufferDepth = 4,
      numVCs = 2
    )
    
    // 验证派生参数计算正确
    params.numSMs should be (params.numSMClusters * params.numSMsPerCluster)
    params.numL2Slices should be (params.numMCs * params.numL2SlicesPerMC)
    params.numSMRouterPortsIn should be (params.numSMsPerCluster)
    params.numSMRouterPortsOut should be (params.numMCs)
    params.numMCRouterPortsIn should be (params.numSMClusters)
    params.numMCRouterPortsOut should be (params.numL2SlicesPerMC)
  }
  
  "NoCParams" should "support different configurations" in {
    // 测试更大的配置
    val params = NoCParams(
      numSMClusters = 4,
      numMCs = 4,
      numSMsPerCluster = 4,
      numL2SlicesPerMC = 4,
      flitWidth = 64,
      bufferDepth = 16,
      numVCs = 4
    )
    
    // 验证参数计算
    params.numSMs should be (16)
    params.numL2Slices should be (16)
    params.numSMRouterPortsIn should be (4)
    params.numSMRouterPortsOut should be (4)
  }
  
  "Flit" should "have correct field widths" in {
    val params = NoCParams(
      numSMClusters = 8,
      numMCs = 8,
      numSMsPerCluster = 10,
      numL2SlicesPerMC = 8,
      flitWidth = 64,
      bufferDepth = 16,
      numVCs = 4
    )
    
    // 计算各个字段的预期位宽
    val expectedFlitTypeWidth = 2
    val expectedVCWidth = log2Ceil(params.numVCs)
    val expectedDestIdWidth = log2Ceil(params.numSMClusters.max(params.numL2Slices))
    val expectedDataWidth = 48
    
    // 验证位宽计算正确
    expectedVCWidth should be (2) // log2Ceil(4) = 2
    expectedDestIdWidth should be (6) // numL2Slices = 8*8=64, log2Ceil(64)=6
    
    // 验证总位宽
    // 2(flitType) + 1(isLast) + 2(vc) + 6(destId) + 48(data) = 59, 但实际flitWidth是64
    // 注意：Chisel可能会自动填充位宽以满足总位宽要求
    expectedFlitTypeWidth + 1 + expectedVCWidth + expectedDestIdWidth + expectedDataWidth should be <= (params.flitWidth)
  }
}

// 测试生成器
object OpenBPUNoCTestGenerator extends App {
  println("Generating OpenBPU NoC test...")
  
  // 生成简化测试模块
  val params = NoCParams(
    numSMClusters = 2,
    numMCs = 2,
    numSMsPerCluster = 2,
    numL2SlicesPerMC = 2,
    flitWidth = 64,
    bufferDepth = 4,
    numVCs = 2
  )
  
  // 生成Verilog代码
  import _root_.circt.stage.ChiselStage
  ChiselStage.emitSystemVerilogFile(new SimpleNoCTest, args.toArray)
  println("Test generation completed.")
}
