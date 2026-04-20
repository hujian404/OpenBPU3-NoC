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

### 2.5 流控机制
采用基于信用的虚拟直通流控制（Credit-Based Virtual Cut-Through）：
- 每个Flit包含一个信用位，用于表示Flit是否可以立即被转发
- 发送方在发送Flit时，根据接收方的信用位判断是否可以立即转发
- 接收方在接收Flit时，根据Flit的信用位更新发送方的信用位

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

#### 3.5.5 流控与公平性实现细节更新

- **输入缓冲与信用模型**：
  - `InputBuffer` 采用“空槽计数”模型：`creditCounter` 复位为 `bufferDepth`，表示本地可接收的空槽数量，上游仅在 `creditCounter > 0` 时才能成功注入；
  - `creditCounter` 更新规则为 `creditNext = creditCounter + creditIn - (in.fire ? 1)`，并在 \[0, bufferDepth\] 范围内钳制，同时添加断言 `creditCounter <= bufferDepth.U` 防止协议违例；
  - `VCInputBuffer` 为每个 VC 实例化独立的 `InputBuffer`，支持 per-VC 流控。

- **链路级信用与 CountingQueue**：
  - 在 ST→LT 阶段，为每个输出端口/VC 使用 `CountingQueue[Flit](depth=2)` 替代裸 `Queue`；
  - `CountingQueue` 内部维护占用计数 `io.count`，并断言 `count <= depth`、空队列不应对外暴露 `deq.valid`；
  - 输出端口的 `creditOut(port)(vc)` 精确计算为 `depth - occupancy`，再截断到 `creditWidth` 位，确保上游不会在链路队列已满时继续注入。

- **RC/VA/SA 轮询公平性**：
  - 在 SM-Router / MC-Router 内部，为每个输入端口维护独立的轮询指针：
    - `rcRrPtr`：RC 阶段在多个 VC 之间进行 round-robin 选择；
    - `vaRrPtr`：VA 阶段在具有授权的 input VC 之间进行 round-robin 选择；
    - `saRrPtr`：SA 阶段在获得 switch grant 的 input VC 之间进行 round-robin 选择；
  - 通过掩码 + `PriorityEncoder` 的方式实现“带起点的优先级轮询”，在长期运行下可避免固定优先级导致的小编号 VC 饥饿。

- **仲裁与 VC 合并断言**：
  - `VCArbiter`：对每个 VC 断言同周期内 grant 向量 onehot0，且 `sel` 与实际 grant 源一致；
  - `RouterArbiter`：对每个输出端口/VC 断言多输入 grant onehot0，防止 crossbar 结构性冲突；
  - `VCMerger`：对单输出端断言 per-cycle grant onehot0，保证单个物理链路在一个周期内只由一个 VC 驱动。
- **缓冲与队列断言**：
  - `InputBuffer`：断言信用计数器不超出缓冲深度，防止缓冲溢出；
  - `CountingQueue`：断言队列占用不超出队列深度，且空队列时不会输出有效信号。

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
| CountingQueue.scala | 带占用计数与安全断言的通用队列封装，用于精确导出信用 |
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
| BufferSpec.scala | `InputBuffer` 基础单元测试（信用计数与FIFO行为验证） |
| VCAllocatorSpec.scala | `VCAllocator` 单元测试（请求→grant 行为验证） |
| RouterArbiterSpec.scala | `RouterArbiter` 单元测试（输出端口互斥授予验证） |
| InputBufferCreditSpec.scala | `InputBuffer` 上电初值与信用不溢出验证 |
| CountingQueueSpec.scala | `CountingQueue` 占用计数行为验证 |
| CreditFlowSpec.scala | 信用为 0 时禁止越界注入验证 |
| RouterEndToEndSpec.scala | 单 flit 端到端连通性与内容正确性验证 |
| MultiSourceHotspotSpec.scala | 多源热点流量注入 forward-progress 验证 |

## 6. 编译和测试结果

### 6.1 Mill编译结果
```
mill MyNoC.compile
[11/33] MyNoC.compileResources
```
编译成功，生成类文件位于 `out/MyNoC/compile.dest/classes`。

### 6.2 Mill测试结果
```
mill MyNoC.test
[82/82] MyNoC.test.test 
RouterEndToEndSpec:
OpenBPUNoC
- should forward a flit from SM to L2 end-to-end
MultiSourceHotspotSpec:
OpenBPUNoC multi-source hotspot
- should deliver all hotspot flits from multiple SMs to a single L2 without deadlock
OpenBPUNoCSpec:
NoCParams
- should calculate correct derived parameters
NoCParams
- should support different configurations
Flit
- should have correct field widths
RouterArbiterSpec:
RouterArbiter
- should grant at most one input per output port
BufferSpec:
InputBuffer
- should respect credits and update creditOut
VCAllocatorSpec:
VCAllocator
- should grant a single requester when only one requests
CountingQueueSpec:
CountingQueue
- should track occupancy correctly
InputBufferCreditSpec:
InputBuffer
- should start with full credit and never overflow
CreditFlowSpec:
NoC credit
- should not allow injection when downstream has zero credit
```
测试成功通过，使用chiseltest 6.0.0与chisel 6.2.0兼容版本。

当前版本（v1.2）已完全支持mill构建系统，所有编译和测试命令均可正常执行。所有9个测试用例均成功通过，覆盖了从单元测试到端到端测试的全面验证。

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
- **模块级边界行为**：`BufferSpec.scala`、`VCAllocatorSpec.scala`、`RouterArbiterSpec.scala`、`CountingQueueSpec.scala` 和 `InputBufferCreditSpec.scala` 对 `InputBuffer`、`VCAllocator`、`RouterArbiter`、`CountingQueue` 等关键模块的边界条件与信用计数逻辑进行单元验证。
- **协议与流控**：`CreditFlowSpec.scala` 验证在下游信用为 0 时禁止越界注入的安全性；`InputBufferCreditSpec.scala` 验证上电后信用初值与不溢出性质。
- **端到端功能**：`RouterEndToEndSpec.scala` 将一个 flit 从 SM 注入，观察 L2 是否在有限周期内接收，用于验证流水线与路径连通性。
- **压力与竞争场景**：`MultiSourceHotspotSpec.scala` 构造多源热点流量，在有限周期内检查所有 SM 均能完成目标注入，验证在 RC/VA/SA 轮询仲裁下的 forward-progress。

这些测试在源码层（`src/test/scala/openbpu/`）以 `ChiselScalatestTester` 与 ScalaTest 编写，既包含快速单元回归，又包含简化的端到端和热点场景集成验证。

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

1. 仲裁策略：在现有 round-robin 基础上进一步考虑 iSLIP 等高级仲裁算法，增强在高度热点/不均匀流量下的公平性；
2. 完善地址映射策略；
3. 完善接口规范和功能测试：围绕多源/多 VC/多类型消息构建可配置的流量生成器与可复用 scoreboard，便于脚本或 AI 自动生成随机流量与约束场景；
4. 形式化验证：针对“无丢包”“无多发”“grant onehot”“credit 不溢出”等关键性质提炼 SystemVerilog/Chisel 级属性，对小规模拓扑进行有限状态形式化证明；
5. 仿真与性能分析：集成 Verilator/VCS/Questa 等仿真工具，执行更大规模、长周期的压力仿真，对延迟、吞吐、队列高水位、热点行为进行统计分析；
6. 一致性支持：补充内存连续性与缓存一致性相关的消息类型与 VC 规划，为未来扩展一致性消息流留足接口与验证钩子；
7. 异构互连：适配 NVLink / UALink 等协议的仿真 stub，将当前 NoC 作为片上互连子网，对接跨芯片通信场景；
8. 动态优化：在保持当前确定性路由的基础上，探索自适应路由、负载均衡与功耗感知调度策略，并通过新增流量模型与断言验证其安全性与收益。

---

**文档版本**：v1.3
**更新日期**：2025年12月26日
**作者**：H.J
