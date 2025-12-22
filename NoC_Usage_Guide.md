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
# 运行测试
mill MyNoC.test
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

**文档版本**：v1.0
**创建日期**：2025年12月18日
**作者**：H.J