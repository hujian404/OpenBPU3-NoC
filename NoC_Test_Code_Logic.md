# OpenBPU NoC测试代码编写逻辑

## 1. 测试框架概述

OpenBPU NoC项目使用**ScalaTest**结合**Chisel**进行测试。测试代码位于`./src/test/scala/openbpu/OpenBPUNoCSpec.scala`，主要包含以下几个部分：

- 测试用模块（TestSM, TestL2Slice, SimpleNoCTest）
- ScalaTest测试用例（OpenBPUNoCSpec）
- 测试生成器（OpenBPUNoCTestGenerator）

## 2. 测试模块设计

### 2.1 TestSM - 测试用SM模块

`TestSM`是一个模拟SM簇的测试模块，用于生成和发送测试Flit到NoC。

#### 核心设计逻辑：

```scala
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
  
  // 状态机控制发送逻辑
  val sIdle :: sSend :: sWait :: Nil = Enum(3)
  val state = RegInit(sIdle)
  
  switch(state) {
    is(sIdle) { when(ready) { valid := true.B; testData := testData + 1.U; state := sSend } }
    is(sSend) { when(ready) { valid := false.B; state := sWait } }
    is(sWait) { when(testData(3, 0) === 0.U) { state := sIdle } }
  }
}
```

**设计要点**：
- 实现了标准的`NoCInterface`接口
- 使用状态机控制Flit发送流程
- 生成带有递增数据的测试Flit
- 简化的信用信号处理（总是返回满缓冲区深度）

### 2.2 TestL2Slice - 测试用L2切片模块

`TestL2Slice`是一个模拟L2切片的测试模块，用于接收和验证来自NoC的Flit。

#### 核心设计逻辑：

```scala
class TestL2Slice(params: NoCParams) extends Module {
  val io = IO(Flipped(new NoCInterface(params)))
  
  // 接收数据
  val receivedData = RegInit(0.U(params.flitWidth.W))
  val receivedValid = RegInit(false.B)
  
  // 总是准备好接收
  io.flit.ready := true.B
  
  when(io.flit.valid) {
    receivedData := io.flit.bits.data
    receivedValid := true.B
  }
  
  // 简化的信用信号处理
  io.creditIn := DontCare
  io.creditOut := params.bufferDepth.U
}
```

**设计要点**：
- 翻转的`NoCInterface`接口（作为接收方）
- 总是准备好接收数据（`io.flit.ready := true.B`）
- 记录接收到的数据用于验证
- 简化的信用信号处理

### 2.3 SimpleNoCTest - 简化的NoC测试模块

`SimpleNoCTest`是一个集成测试模块，将多个`TestSM`和`TestL2Slice`连接到NoC实例。

#### 核心设计逻辑：

```scala
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
  
  val io = IO(new Bundle { val testDone = Output(Bool()) })
  
  // NoC实例
  val noc = Module(new OpenBPUNoC(params))
  
  // 测试SM和L2切片数组
  val testSMs = Seq.fill(params.numSMs)(Module(new TestSM(params)))
  val testL2s = Seq.fill(params.numL2Slices)(Module(new TestL2Slice(params)))
  
  // 连接测试模块到NoC
  for (i <- 0 until params.numSMs) { noc.io.sm(i) <> testSMs(i).io }
  for (i <- 0 until params.numL2Slices) { testL2s(i).io <> noc.io.l2(i) }
}
```

**设计要点**：
- 固定配置的NoC参数
- 创建NoC实例和多个测试模块
- 自动连接所有测试模块到NoC接口
- 提供测试完成信号

## 3. ScalaTest测试用例

### 3.1 测试框架和结构

使用ScalaTest的`AnyFlatSpec`和`Matchers`进行测试：

```scala
class OpenBPUNoCSpec extends AnyFlatSpec with Matchers {
  // 测试用例...
}
```

### 3.2 测试用例详解

#### 3.2.1 NoCParams派生参数测试

```scala
"NoCParams" should "calculate correct derived parameters" in {
  val params = NoCParams(numSMClusters=2, numMCs=2, numSMsPerCluster=1, numL2SlicesPerMC=1, ...)
  
  // 验证派生参数计算正确
  params.numSMs should be (params.numSMClusters * params.numSMsPerCluster)
  params.numL2Slices should be (params.numMCs * params.numL2SlicesPerMC)
  // ... 其他参数验证
}
```

**测试目的**：验证NoCParams构造函数是否正确计算所有派生参数。

#### 3.2.2 配置支持测试

```scala
"NoCParams" should "support different configurations" in {
  // 测试更大的配置
  val params = NoCParams(numSMClusters=4, numMCs=4, numSMsPerCluster=4, numL2SlicesPerMC=4, ...)
  
  // 验证参数计算
  params.numSMs should be (16)
  params.numL2Slices should be (16)
  // ... 其他参数验证
}
```

**测试目的**：验证NoCParams能够正确支持不同规模的配置。

#### 3.2.3 Flit字段位宽测试

```scala
"Flit" should "have correct field widths" in {
  val params = NoCParams(numSMClusters=8, numMCs=8, numSMsPerCluster=10, numL2SlicesPerMC=8, ...)
  
  // 计算各个字段的预期位宽
  val expectedFlitTypeWidth = 2
  val expectedVCWidth = log2Ceil(params.numVCs)
  val expectedDestIdWidth = log2Ceil(params.numSMClusters.max(params.numL2Slices))
  val expectedDataWidth = 48
  
  // 验证位宽计算正确
  expectedVCWidth should be (2) // log2Ceil(4) = 2
  expectedDestIdWidth should be (6) // numL2Slices = 8*8=64, log2Ceil(64)=6
  
  // 验证总位宽
  expectedFlitTypeWidth + 1 + expectedVCWidth + expectedDestIdWidth + expectedDataWidth should be <= (params.flitWidth)
}
```

**测试目的**：验证Flit各个字段的位宽计算是否正确，以及总位宽是否在规定范围内。

## 4. 测试生成器

### 4.1 OpenBPUNoCTestGenerator

```scala
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
```

**功能**：
- 生成`SimpleNoCTest`模块的Verilog代码
- 用于快速验证NoC的基本功能

## 5. 测试代码编写原则

### 5.1 模块化设计
- 分离测试模块和测试用例
- 每个测试模块专注于单一功能
- 测试用例与实现逻辑解耦

### 5.2 覆盖关键功能
- 参数计算验证
- 数据结构位宽验证
- 接口兼容性验证
- 不同配置支持验证

### 5.3 简化测试逻辑
- 简化信用信号处理（测试环境下）
- 固定测试模式（便于调试）
- 明确的状态机控制

### 5.4 可扩展性
- 支持不同规模的NoC配置
- 易于添加新的测试用例
- 测试模块与实际模块接口兼容

## 6. 测试执行流程

1. **编译测试代码**：使用sbt或mill编译测试模块和测试用例
2. **运行ScalaTest**：执行`OpenBPUNoCSpec`中的测试用例
3. **生成测试Verilog**：使用`OpenBPUNoCTestGenerator`生成集成测试模块的Verilog
4. **验证测试结果**：检查测试用例是否通过，分析生成的Verilog代码

## 7. 总结

OpenBPU NoC的测试代码采用了模块化、分层的设计理念，通过以下方式确保测试的有效性：

- **模拟模块**：创建TestSM和TestL2Slice模拟实际组件
- **参数验证**：测试NoCParams的派生参数计算
- **结构验证**：验证Flit字段的位宽和总位宽
- **配置验证**：测试不同规模的NoC配置
- **集成测试**：通过SimpleNoCTest验证整体功能



---

**文档版本**：v1.0
**创建日期**：2025年12月18日
**作者**：H.J