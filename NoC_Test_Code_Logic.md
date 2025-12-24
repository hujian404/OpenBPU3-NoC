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
### 2.4 新增单元测试

为提高关键模块的回归覆盖，新增以下基于 ChiselTest 的单元测试：

- `BufferSpec.scala`：验证 `InputBuffer` 的信用计数、入队/出队语义以及边界行为。
- `VCAllocatorSpec.scala`：验证 `VCAllocator` 在单一或并发请求下的 grant 行为。
- `RouterArbiterSpec.scala`：验证 `RouterArbiter` 对同一输出端口的互斥授予（最多一个输入获 grant）。

这些测试位于 `src/test/scala/openbpu/`，并使用 `ChiselScalatestTester` 的 `poke`/`peek` 接口进行激励与断言。
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

示例命令（sbt）：

```bash
# 编译
sbt compile

# 运行所有测试
sbt test

# 仅运行新增的单元测试
sbt "testOnly *VCAllocatorSpec"
sbt "testOnly *RouterArbiterSpec"
sbt "testOnly *BufferSpec"
# mill - 通过 ScalaTest 的 `-z` 模式选择匹配的测试名
mill MyNoC.test -- -z "VCAllocatorSpec"
mill MyNoC.test -- -z "RouterArbiterSpec"
mill MyNoC.test -- -z "BufferSpec"
```

注意：在一些环境或 mill 版本中，尝试通过 `mill ... -- -z "<pattern>"` 把参数传给 ScalaTest 会导致 ScalaTest 报错（例如收到未识别的 `--` 参数）。如果发生此类问题，请优先使用 `sbt "testOnly *<SpecName>"` 来做选择性测试，或在 `build.sc` 中添加一个自定义 mill 任务以接受并转发测试过滤参数。

## 7. 总结

OpenBPU NoC的测试代码采用了模块化、分层的设计理念，通过以下方式确保测试的有效性：

- **模拟模块**：创建TestSM和TestL2Slice模拟实际组件
- **参数验证**：测试NoCParams的派生参数计算
- **结构验证**：验证Flit字段的位宽和总位宽
- **配置验证**：测试不同规模的NoC配置
- **集成测试**：通过SimpleNoCTest验证整体功能
- **断言验证**：在关键模块（InputBuffer、CountingQueue）中添加安全断言，防止溢出和无效状态
- **全面测试覆盖**：新增单元测试（CountingQueueSpec、InputBufferCreditSpec）和集成测试（MultiSourceHotspotSpec），确保NoC在各种场景下的正确性和可靠性

## 8. 测试文件快速索引与说明

- **OpenBPUNoCSpec.scala**: 顶层功能与参数化测试集合。包含：`NoCParams`派生参数测试、不同配置支持测试、以及`Flit`字段位宽/总宽度一致性检查。该文件也包含 `TestSM` / `TestL2Slice` / `SimpleNoCTest` 的 RTL 测试模块生成逻辑（可用于生成 SystemVerilog 以作更高层级验证）。
- **BufferSpec.scala**: 使用 `ChiselScalatestTester` 对 `InputBuffer` 的信用计数、入队/出队语义和边界行为进行单元测试（通过 `poke` / `peek` 驱动和断言）。
- **VCAllocatorSpec.scala**: 验证 `VCAllocator` 在单输入请求或并发请求下的 grant 行为，确保每个输出 VC 的分配公平性。此测试直接对 `VCAllocator` 模块施加请求向量并检查 `grant` 输出。
- **RouterArbiterSpec.scala**: 验证 `RouterArbiter` 在多个输入同时请求同一输出端口时的互斥性（最多仅一个输入获 grant），用于证明交换片层面仲裁正确性。
- **RouterEndToEndSpec.scala**: 端到端功能测试，将一个 flit 从 SM 注入并观察 L2 是否在有限周期内接收，验证整体路径（包含缓冲、路由计算、仲裁和链路）连通性。
- **CreditFlowSpec.scala**: 验证信用流控制行为：当下游信用为 0 时，NoC 应阻止上游注入，确保流量控制安全性。
- **CountingQueueSpec.scala**: 验证 `CountingQueue` 的占用计数和边界行为，确保队列状态与实际占用一致。
- **InputBufferCreditSpec.scala**: 验证 `InputBuffer` 的信用计数机制，确保信用不会溢出且正确控制输入流量。
- **MultiSourceHotspotSpec.scala**: 验证多源热点场景下 NoC 的性能和可靠性，确保所有 flit 都能正确交付且无死锁。

## 9. 设计中的断言机制

### 9.1 断言概述

为提高系统可靠性和可验证性，在设计代码中添加了以下关键断言：

#### 9.1.1 InputBuffer 断言

```scala
// 简单的安全断言：在仿真/形式化中捕获明显协议违例
assert(creditCounter <= creditMax, "InputBuffer creditCounter overflow")
```

**目的**：确保信用计数器不会超过缓冲深度，防止缓冲溢出。

#### 9.1.2 CountingQueue 断言

```scala
// 安全断言（仿真/形式化专用）
assert(countReg <= max, "CountingQueue occupancy overflow")
when (countReg === 0.U) {
  // 队列空时不应有 deq.valid
  assert(!io.deq.valid, "CountingQueue reports empty but deq.valid is high")
}
```

**目的**：
- 确保队列占用计数不会溢出
- 确保队列为空时不会输出有效信号

## 10. 测试编写与验证要点（实战提示）

- 使用 `ChiselScalatestTester` 的 `test` 框架时，尽量在测试内对 `creditIn` / `creditOut` 做显式初始化，避免因默认值导致的不可重复行为。
- 对于需要观测跨阶段传播的信号（例如 RC→VA→SA 的延迟），在断言前多跑几个 `clock.step()` 以允许管线推进。
- 单元测试优先覆盖模块边界行为（例如 `InputBuffer` 的边界条件、VCAllocator 的竞争情形），集成测试用于覆盖端到端时序与协议交互。

## 11. 生成 Verilog 与集成验证推荐流程

1. 使用 `sbt` 或 `mill` 编译（见使用指南）。
2. 用 `sbt` / `mill` 运行 ScalaTest 单元测试，确保所有单元测试通过。
3. 使用 `OpenBPUNoCTestGenerator` 或项目中的 `NoCGenerator` 生成 SystemVerilog：

```bash
# 使用 sbt
sbt "runMain openbpu.OpenBPUNoCTestGenerator"

# 使用 mill (若 build.sc 中已定义 runMain)
mill MyNoC.runMain openbpu.OpenBPUNoCTestGenerator
```

4. 将生成的 `generated/OpenBPUNoC.sv` 在 Verilator / Questa / VCS 中做功能仿真，接入更复杂的 TB 驱动以验证真实负载场景。

---

**文档版本**：v1.2
**更新日期**：2025年12月24日
**作者**：H.J
