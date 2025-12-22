更多测试使用与命令请参阅使用指南：[NoC_Usage_Guide.md](NoC_Usage_Guide.md)。

注意：使用 mill 运行选择性测试时，部分 mill 版本/配置无法正确将 `--` 分隔符与后续 ScalaTest 参数转发到 ScalaTest（会出现 "Argument unrecognized by ScalaTest's Runner: --" 错误）。遇到该问题时建议使用 `sbt "testOnly *<SpecName>"`，或在 `build.sc` 中添加自定义 mill 测试目标以支持参数转发。

# OpenBPU NoC设计文档

## 1. 项目概述

### 1.1 项目目标
构建对标GPU的OpenBPU第三代NoC（Network-on-Chip），采用GPU Hierarchical-Xbar两级路由架构，实现SM核与访存系统的高效互连。

### 1.2 核心功能
- 两级路由架构：SM-Router + MC-Router
- 支持数据、请求、响应和控制消息类型
- 基于信用的流量控制
- 虚拟通道（VC）机制
- Round-Robin仲裁算法
- 参数化配置支持

## 2. 架构设计

### 2.1 拓扑结构
采用两级Hierarchical-Xbar路由架构。

### 2.2 数量关系
- MC-Router数量 = 内存控制器数量
- SM-Router数量 = SM集群数量 = 每个内存控制器下的LLC切片数量
- 示例配置：8个MC-Router、8个SM-Router、每个SM-Router连接10个SM、每个MC-Router连接8个L2切片

### 2.3 数据流向
SM（L1/Shared Memory）→ SM-Router → MC-Router → L2切片

### 2.4 互联规则
- SM-Router仅连接本集群内所有SM
- 所有SM-Router与MC-Router全互连
- MC-Router仅连接对应内存控制器的L2切片
- 无SM间、LLC切片间直接通信

## 3. 核心组件设计

### 3.1 Flit格式

- 字段位宽由 `NoCParams` 动态计算，保证 `flitWidth` 可配置并且头部/数据区灵活分配；实现中按以下原则：
  - `flitType` (2 位)：DATA / REQUEST / RESPONSE / CONTROL
  - `isLast` (1 位)：是否为最后一个 Flit
  - `vc` (log2Ceil(params.numVCs) 位)：虚拟通道 ID
  - `destId` (log2Ceil(params.numSMs.max(params.numL2Slices)) 位)：目标 ID（低位用于 GPU-friendly 切片映射）
  - `data` (params.flitWidth - headerWidth 位)：数据负载

说明：此前文档中以固定 64 位示例给出字段划分，当前实现已改为参数化计算 `destId` 与 `data` 的位宽，以便适配不同的 `params.flitWidth` 和 GPU 规模映射要求。

### 3.2 Router设计范式

严格遵循：输入端口→输入缓冲器→RC（路由计算）→Arbiter（仲裁）→Output Mux→输出寄存器

#### 3.2.1 输入缓冲器（InputBuffer）
- 基于信用的流量控制
- 支持虚拟通道
- 深度可配置（默认16）

#### 3.2.2 路由计算（RC）
- SM-RouterRC：计算SM到MC-Router的路由
- MCRouterRC：计算MC-Router到L2切片的路由

#### 3.2.3 仲裁器（Arbiter）
- Round-Robin仲裁算法
- 支持多端口优先级调度
- 公平性保证

#### 3.2.4 输出多路选择器（OutputMux）
- 1-Hot选择逻辑
- 支持多端口输入

### 3.3 SM-Router

- 输入端口：SM集群内所有SM + 全局互连端口
- 输出端口：全局互连端口
- 核心功能：将SM数据路由到相应的MC-Router

### 3.4 MC-Router

- 输入端口：全局互连端口
- 输出端口：对应内存控制器的L2切片
- 核心功能：将SM-Router数据路由到相应的L2切片

### 3.5 路由器流水线设计

#### 3.5.1 流水线概述
OpenBPU NoC路由器采用6级流水线架构，实现高效的Flit转发。SM-Router和MC-Router均遵循相同的流水线设计范式，确保设计一致性和可维护性。流水线阶段包括：
- **BW** (Buffer Write) - 缓冲器写入阶段
- **RC** (Route Compute) - 路由计算阶段
- **VA** (VC Allocation) - 虚拟通道分配阶段
- **SA** (Switch Allocation) - 开关分配阶段
- **ST** (Switch Traversal) - 切换遍历阶段
- **LT** (Link Traversal) - 链接遍历阶段

每个阶段之间通过流水线寄存器隔离，实现高频率操作和数据并行处理。

#### 3.5.2 流水线阶段详细说明

##### 1. BW: Buffer Write - 缓冲器写入阶段
- **功能**：接收输入Flit并写入相应的虚拟通道(VC)缓冲器
- **核心模块**：`VCInputBuffer`
- **输入**：来自上游的Flit和信用信号
- **输出**：写入VC缓冲器的Flit数据和有效信号
- **关键操作**：
  - 根据VC ID将Flit写入对应缓冲
  - 维护信用计数，防止缓冲溢出
  - 生成输出信用信号反馈给上游

##### 2. RC: Route Compute - 路由计算阶段
- **功能**：根据Flit的目标ID计算输出端口
- **核心模块**：
  - `SMRouterRC` (SM-Router专用)
  - `MCRouterRC` (MC-Router专用)
- **输入**：来自BW阶段的Flit数据
- **输出**：计算得到的输出端口号和VC号
- **关键操作**：
  - 解析Flit中的`destId`字段
  - 执行路由算法确定输出端口
  - 选择合适的虚拟通道

##### 3. VA: VC Allocation - 虚拟通道分配阶段
- **功能**：为待转发的Flit分配输出虚拟通道
- **核心模块**：`VCAllocator`
- **输入**：来自RC阶段的输出端口和VC请求
- **输出**：VC分配授权
- **关键操作**：
  - 跟踪每个输出端口各VC的可用性
  - 使用公平算法分配VC资源
  - 处理VC冲突情况

##### 4. SA: Switch Allocation - 开关分配阶段
- **功能**：为已分配VC的Flit分配输出开关端口
- **核心模块**：`RouterArbiter`
- **输入**：来自VA阶段的VC分配结果和输出端口请求
- **输出**：开关分配授权和选择信号
- **关键操作**：
  - 收集来自所有输入VC的开关请求
  - 使用Round-Robin算法进行公平仲裁
  - 生成开关选择信号

##### 5. ST: Switch Traversal - 切换遍历阶段
- **功能**：通过交叉开关将Flit从输入端口传输到输出端口
- **核心模块**：`RouterOutputMux`
- **输入**：来自SA阶段的Flit数据和开关选择信号
- **输出**：通过交叉开关后的Flit数据
- **关键操作**：
  - 根据开关选择信号路由Flit
  - 处理数据并行传输
  - 维护开关状态

##### 6. LT: Link Traversal - 链接遍历阶段
- **功能**：将Flit传输到输出链路并处理链路信用
- **核心模块**：`VCMerger`
- **输入**：来自ST阶段的Flit数据
- **输出**：最终的输出Flit和信用信号
- **关键操作**：
  - 合并同一输出端口的多个VC数据
  - 生成输出信用信号
  - 处理链路级流控

#### 3.5.3 流水线控制流

```
输入Flit → [BW] → [RC] → [VA] → [SA] → [ST] → [LT] → 输出Flit
  ↑                                                 |
  |                                                 |
  └─────────────────────────────────────────────────┘
                信用反馈路径
```

- **正向流**：Flit数据依次通过6个流水线阶段
- **反向流**：信用信号从下游向上游反馈，用于流量控制
- **流水线寄存器**：每个阶段之间插入寄存器，实现时钟域隔离和高频率运行

#### 3.5.4 流水线优化策略

1. **虚拟通道并行**：不同VC的数据可以在流水线中并行处理
2. **信用预分配**：提前为下一个Flit分配信用，减少等待时间
3. **仲裁预测**：使用历史信息预测仲裁结果，加速SA阶段
4. **端口优先级**：支持对关键端口设置优先级，优化关键路径性能
5. **流水线停顿控制**：当下游资源不可用时，通过ready信号控制流水线停顿

实现注记：最近实现对流水线做了进一步改造——RC、VA、SA、ST 之间的边界在多个位置用短深度 `Queue` (depth=2) 队列化，以减少大量的 RegNext 寄存器并提供弹性 backpressure：
- RC 输入改为 `Decoupled(Flit)`，便于在 RC 前后插入队列；
- 已实现队列化的边界：RC→VA、VA→SA、SA→ST、ST→LT（ST→LT 使用 per-output/VC 的短队列）；
- 该改动提升了对反压的适应能力并简化了 ready/valid 管线控制逻辑。

## 4. 实现细节

### 4.1 参数化配置

```scala
case class NoCParams(
  numSMClusters: Int = 8,    // SM集群数量
  numMCs: Int = 8,           // 内存控制器数量
  numSMsPerCluster: Int = 10,// 每个SM集群的SM数量
  numL2SlicesPerMC: Int = 8, // 每个MC的L2切片数量
  flitWidth: Int = 64,       // Flit宽度
  bufferDepth: Int = 16,     // 输入缓冲深度
  numVCs: Int = 2            // 虚拟通道数量
)
```

### 4.2 接口定义

```scala
class NoCInterface(params: NoCParams) extends Bundle {
  // Flit 使用 Decoupled 握手（支持在 RC 前后插入 Queue）
  val flit = Decoupled(new Flit(params))

  // 信用改为 per-VC 向量：每个 VC 单独计数，便于精细流控
  val creditIn = Input(Vec(params.numVCs, UInt(log2Ceil(params.bufferDepth + 1).W)))
  val creditOut = Output(Vec(params.numVCs, UInt(log2Ceil(params.bufferDepth + 1).W)))
}
```

### 4.3 信用制流量控制

- 跟踪下游缓冲可用性
- 防止缓冲溢出
- 支持多虚拟通道独立控制

### 4.4 虚拟通道（VC）机制

- 物理上分离的子网
- 独立的缓冲和仲裁
- 减少死锁风险

### 4.5 Round-Robin仲裁

- 公平调度请求
- 优先级轮换机制
- 支持多端口输入

## 5. 代码结构

### 5.1 核心文件

| 文件名 | 功能描述 |
|-------|---------|
| NoCParams.scala | 参数化配置定义 |
| NoCInterface.scala | Flit和NoC接口定义 |
| InputBuffer.scala | 输入缓冲器实现 |
| RoutingUnit.scala | 路由计算单元 |
| Arbiter.scala | 仲裁器实现 |
| OutputMux.scala | 输出多路选择器 |
| SMRouter.scala | SM-Router实现 |
| MCRouter.scala | MC-Router实现 |
| OpenBPUNoC.scala | NoC顶层模块 |
| NoCGenerator.scala | Verilog生成器 |

### 5.2 测试文件

| 文件名 | 功能描述 |
|-------|---------|
| OpenBPUNoCSpec.scala | NoC功能测试 |
| BufferSpec.scala | `InputBuffer` 单元测试（信用计数与FIFO行为验证） |
| VCAllocatorSpec.scala | `VCAllocator` 单元测试（请求→grant 行为验证） |
| RouterArbiterSpec.scala | `RouterArbiter` 单元测试（输出端口互斥授予验证） |

## 6. 编译和测试结果

### 6.1 Mill编译结果
```
mill MyNoC.compile
[34/34] MyNoC.compile
```
编译成功，生成类文件位于 `out/MyNoC/compile.dest/classes`。

### 6.2 Mill测试结果
```
mill MyNoC.test
[82/82] MyNoC.test.test 
OpenBPUNoCSpec:
NoCParams
- should calculate correct derived parameters
NoCParams
- should support different configurations
Flit
- should have correct field widths
```
测试成功通过，使用chiseltest 6.0.0与chisel 6.2.0兼容版本。

### 6.3 Sbt编译结果
```
sbt compile
[success] Total time: 2 s
```
编译成功，生成类文件位于 `target/scala-2.13/classes`。

### 6.4 Sbt测试结果
```
sbt test
[info] OpenBPUNoCSpec:
[info] NoCParams
[info] - should calculate correct derived parameters
[info] NoCParams
[info] - should support different configurations
[info] Flit
[info] - should have correct field widths
[info] Run completed in 142 milliseconds.
[info] Total number of tests run: 3
[info] Suites: completed 1, aborted 0
[info] Tests: succeeded 3, failed 0, canceled 0, ignored 0, pending 0
[info] All tests passed.
[success] Total time: 15 s
```
测试成功通过，已添加chiseltest 6.0.0依赖。

### 6.5 Verilog生成
成功生成 `OpenBPUNoC.sv` 文件，位于 `generated` 目录。

## 7. 测试覆盖与验证方法

本工程配套的 Scala/Chisel 测试覆盖了以下几个维度：

- **参数与接口正确性**：通过 `OpenBPUNoCSpec.scala` 中对 `NoCParams` 的派生参数测试和 `Flit` 字段位宽检查，确保参数化配置与接口宽度一致性。
- **模块级边界行为**：`BufferSpec.scala`、`VCAllocatorSpec.scala` 和 `RouterArbiterSpec.scala` 对 `InputBuffer`、`VCAllocator`、`RouterArbiter` 等关键模块的边界条件与仲裁逻辑进行单元验证。
- **协议与流控**：`CreditFlowSpec.scala` 验证信用流控制在下游信用为 0 时阻止上游注入的安全性。
- **端到端功能**：`RouterEndToEndSpec.scala` 将一个 flit 从 SM 注入，观察 L2 是否在有限周期内接收，用于验证流水线与路径连通性。

这些测试在源码层（`src/test/scala/openbpu/`）以 `ChiselScalatestTester` 与 ScalaTest 编写，既包含快速单元回归，又包含简化的端到端集成验证。

验证建议流程：

1. 使用 `sbt` 或 `mill` 完整编译工程（参见 [NoC_Usage_Guide.md](NoC_Usage_Guide.md)）。
2. 运行单元测试（`sbt test` 或 `mill MyNoC.test`），优先保证 `BufferSpec`、`VCAllocatorSpec`、`RouterArbiterSpec` 通过。
3. 通过 `OpenBPUNoCTestGenerator` 生成 `SimpleNoCTest` 的 SystemVerilog，用仿真工具（Verilator / VCS / Questa）复现更复杂的时序/负载场景。
4. 将关键失败用例回溯到模块边界（例如检查 `creditIn`/`creditOut` 初值、VC 分配逻辑）并补充断言或额外的单元测试。

更多测试使用与命令请参阅使用指南：[NoC_Usage_Guide.md](NoC_Usage_Guide.md)。

## 7. 性能优化方向

1. 仲裁策略优化
2. 一致性支持增强
3. 异构互连扩展
4. 动态路由优化
5. 功耗管理机制

## 8. 技术亮点

1. **模块化设计**：组件间松耦合，便于维护和扩展
2. **参数化配置**：支持多种拓扑结构和性能参数调整
3. **严格的设计范式**：遵循标准化的路由器设计流程
4. **完整的流量控制**：基于信用的多虚拟通道流量控制
5. **公平的仲裁机制**：Round-Robin算法保证请求调度公平性

## 9. 后续计划（TODO）

1. 仲裁策略：后续可在Round-Robin仲裁基础上进一步考虑 iSLIP 等策略；
2. 完善接口规范和功能测试，进一步验证NoC路由设计正确性；
3. 集成verilator进行仿真测试，并集成更复杂的测试bench模拟真实流量负载；
4. 一致性支持：补充内存连续性与缓存一致性机制，实现对一致性消息的支持通；
5. 异构互连：适配 NVlink /UALink等协议，支持跨芯片通信；
6. 动态优化：添加负载均衡、自适应路由等功能，优化带宽。

---

**文档版本**：v1.0
**创建日期**：2025年12月18日
**作者**：H.J
**文档版本**：v1.1
**更新日期**：2025年12月22日