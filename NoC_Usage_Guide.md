# OpenBPU NoC 使用指南

本文档介绍如何编译、测试和生成OpenBPU NoC的Verilog代码。

## 环境要求

- Java版本：17.0.17 (Eclipse Adoptium)
- Scala版本：2.13.12
- Chisel版本：6.2.0
- Mill版本：0.11.2
- Sbt版本：1.11.7

## 项目结构

```
MyNoC/
├── src/
│   ├── main/scala/openbpu/   # 主源代码目录
│   └── test/scala/openbpu/    # 测试代码目录
├── generated/                 # Verilog生成目录
├── build.sc                  # Mill构建配置
├── build.sbt                 # Sbt构建配置
└── ...
```

## 编译项目

### 使用Mill编译

```bash
# 编译主代码
mill MyNoC.compile
```

### 使用Sbt编译

```bash
# 编译主代码
 sbt compile
```

## 运行测试

### 使用Mill运行测试

```bash
# 运行所有测试
mill MyNoC.test

# 若需要交互式运行（以便诊断），可以加 -i
mill -i MyNoC.test
```

### 使用Sbt运行测试

```bash
# 运行所有测试
 sbt test
```

### 常见场景与示例命令

- 只运行单个 ScalaTest 测试类（推荐用于快速回归）：

```bash
# sbt - 选择性运行某个测试类或匹配模式（推荐方式）
sbt "testOnly *BufferSpec"
sbt "testOnly *RouterArbiterSpec"
sbt "testOnly *VCAllocatorSpec"

# sbt - 选择性运行匹配的测试名
sbt "testOnly -- -z "BufferSpec""

# mill - 当前版本中，由于 mill testrunner 的限制，无法直接将参数传递给 ScalaTest
# 若需要运行单个测试类，请使用 sbt 命令
```

> 注意：在当前 mill 版本中，`--` 分隔符无法正确将参数转发到 ScalaTest（会出现 "Argument unrecognized by ScalaTest's Runner: --" 错误）。推荐使用 `sbt "testOnly *<SpecName>"` 进行选择性测试。

### Troubleshooting（常见故障排查）

- 运行测试时遇到类路径或依赖错误：请先运行 `mill MyNoC.compile` 或 `sbt compile`，确保生成的类文件可见。
- chiseltest 版本或 Scala 版本不兼容：确认 `build.sbt`/`build.sc` 中 `chiselVersion`、`chiseltest` 与 `scalaVersion`（2.13.x）一致。
- 测试运行时看到时序相关断言偶发失败：测试中涉及多级流水线（RC/VA/SA/...）时，请在断言前多推进若干周期，例如 `clock.step(2)`，以容忍 pipeline 延迟。
- 若使用 CI（GitHub Actions 等）运行测试，请在 CI 配置中预安装合适的 JDK、sbt/mill 版本并开启足够的内存（测试/编译时可能需要较大堆内存）。

```

测试依赖已配置为chiseltest 6.0.0，与chisel 6.2.0完全兼容。

### 使用Sbt运行测试

```bash
# 运行测试
 sbt test
```

测试依赖已配置为chiseltest 6.0.0，与chisel 6.2.0完全兼容。

## 生成Verilog代码

### 使用Mill生成

```bash
# 使用NoCGenerator生成Verilog
mill MyNoC.runMain openbpu.NoCGenerator

# 生成的Verilog文件位于：generated/OpenBPUNoC.sv
```

### 手动运行生成器

```bash
# 直接运行生成器
scala -cp "$(mill MyNoC.runClasspath)" openbpu.NoCGenerator
```

## 核心组件说明

### 主源代码文件

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

### 测试文件

| 文件名 | 功能描述 |
|-------|---------|
| OpenBPUNoCSpec.scala | NoC功能测试 |
## 接口与运行要点（快速参考）

- `NoCInterface`：`flit` 为 `Decoupled(new Flit(params))`，`creditIn`/`creditOut` 为 `Vec(params.numVCs, UInt(...))`（每个 VC 单独信用计数）。

- 在当前实现中，RC/VA/SA/ST 等阶段在若干边界处使用短队列（depth=2）队列化，以提供反压弹性；因此测试中可能观察到信号跨 cycle 的偏移。

### 常用命令

```bash
# 编译工程（sbt）
sbt compile

# 运行全部测试
sbt test

# 运行单个测试文件（示例）
sbt "testOnly *VCAllocatorSpec"
sbt "testOnly *RouterArbiterSpec"

# mill - 对应的单测过滤示例（将参数传递给 ScalaTest）
mill MyNoC.test -- -z "VCAllocatorSpec"
mill MyNoC.test -- -z "RouterArbiterSpec"

# 生成 Verilog
sbt "runMain openbpu.NoCGenerator"
```

## 参数配置说明

OpenBPU NoC支持通过`NoCParams`类进行参数化配置，主要参数包括：

- `numSMClusters`：SM集群数量
- `numMCs`：内存控制器数量
- `numSMsPerCluster`：每个SM集群的SM数量
- `numL2SlicesPerMC`：每个MC的L2切片数量
- `flitWidth`：Flit宽度
- `bufferDepth`：输入缓冲深度
- `numVCs`：虚拟通道数量

示例配置：

```scala
val params = NoCParams(
  numSMClusters = 8,
  numMCs = 8,
  numSMsPerCluster = 10,
  numL2SlicesPerMC = 8,
  flitWidth = 64,
  bufferDepth = 16,
  numVCs = 2
)
```



---

**文档版本**：v1.2
**更新日期**：2025年12月24日
**作者**：H.J

