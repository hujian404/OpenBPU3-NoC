# GPGPU-Sim 部署与 Rodinia 测试集运行指南（Ubuntu 24.04 + GV100）

## 📌 概述
本文档记录了在 **Ubuntu 24.04** 系统上从零开始部署 **GPGPU-Sim 4.2.0** 模拟器，配置模拟 **NVIDIA GV100 (Volta)** 架构，并成功运行 **Rodinia 3.1** 基准测试集的完整流程。

**最终环境组合**：
- 操作系统：Ubuntu 24.04 (Noble Numbat)
- 宿主编译器：GCC 11 (通过 `update-alternatives` 降级)
- CUDA 版本：11.8 (与31服务器原先的 CUDA 12.8 共存)
- GPGPU-Sim 版本：4.2.0 (Git 版)
- Rodinia 版本：3.1 (CUDA 实现)
- 模拟 GPU 配置：GV100 (SM 7.0)

---

## 🛠️ 第一部分：环境准备

### 1.1 安装系统依赖包
```bash
sudo apt update
sudo apt install build-essential xutils-dev bison zlib1g-dev flex libglu1-mesa-dev
```

### 1.2 安装 CUDA 11.8（与现有 CUDA 12.8 共存）

CUDA 12.8 下 GPGPU-Sim 编译可能会报 NPP 库链接错误，因此额外安装了 CUDA 11.8。

```bash
# 下载 CUDA 11.8 runfile
wget https://developer.download.nvidia.com/compute/cuda/11.8.0/local_installers/cuda_11.8.0_520.61.05_linux.run

# 安装到独立目录（不覆盖现有环境）
sudo sh cuda_11.8.0_520.61.05_linux.run --toolkit --silent --override --toolkitpath=/usr/local/cuda-11.8
```

验证安装：

```bash
/usr/local/cuda-11.8/bin/nvcc --version   # 应输出 11.8 版本信息
```



------

## 📦 第二部分：获取并编译 GPGPU-Sim

### 2.1 克隆源码仓库

```bash
cd ~/Project
git clone https://github.com/gpgpu-sim/gpgpu-sim_distribution.git
cd gpgpu-sim_distribution
```



### 2.2 设置 CUDA 路径并编译

```bash
export CUDA_INSTALL_PATH=/usr/local/cuda-11.8
export PATH=$CUDA_INSTALL_PATH/bin:$PATH
source setup_environment release
make -j$(nproc)
```



### 2.3 配置 GV100 架构

将预置的 GV100 配置文件复制到当前目录：

```
cp configs/tested-cfgs/SM7_GV100/* .
```



**注意**：若提示 `Not a directory` 错误，请执行：

```
rm -f gpgpusim.config accelwattch*.xml config_volta*.icnt
cp configs/tested-cfgs/SM7_GV100/* .
```



### 2.4 解决 Git 警告（可选）

若遇到 `fatal: not a git repository` 警告，执行：

```
git config --global --add safe.directory $(pwd)
```



------

## 🧪 第三部分：获取并编译 Rodinia 测试集

### 3.1 克隆 Rodinia 仓库
```bash
cd ~/Project
# 注意：此仓库已包含所有源代码和必要的测试数据
git clone https://github.com/kiliakis/gpu-rodinia-3.1.git
cd gpu-rodinia-3.1
```

（若链接失效，请从 [Rodinia 官网](http://lava.cs.virginia.edu/Rodinia/download_links.htm) 获取数据包）



### 3.2 修改编译配置文件

编辑 `common/make.config`，打开文件

```makefile
nano make.config
```

将 CUDA 路径修改为：

```makefile
CUDA_DIR = /usr/local/cuda-11.8
```

修改 `NV_OPENCL_DIR`（如果测试不涉及 OpenCL，此步可选）

```makefile
NV_OPENCL_DIR = /usr/local/cuda-11.8
```

**保存并退出**：

- 按 `Ctrl+O` 保存
- 按 `Enter` 确认文件名
- 按 `Ctrl+X` 退出 nano



### 3.3 解决 CUDA 架构 `sm_13` 不支持问题

Rodinia 3.1 默认编译架构 `sm_13` 在 CUDA 11.x 中已被移除，需批量替换为 `sm_35`。

修改 `common/common.mk`（影响全局编译），打开文件：

```bash
cd ~/Project/gpu-rodinia-3.1/common
nano common.mk
```

找到 `SM_VERSIONS` 那一行（通常被注释掉了，需要你启用并修改）。将其改为：

```makefile
SM_VERSIONS := sm_35
```

批量替换所有 Makefile 中的 sm_13, 使用 `sed` 命令一次性处理所有匹配到的文件：

```bash
# 全局替换 Makefile 中的 sm_13 → sm_35
find . -name "Makefile" -exec sed -i 's/sm_13/sm_35/g' {} \;
```

快速检查一下是否还有残留的 `sm_13`：（如果输出为空，说明全部替换成功）

```bash
grep -r "sm_13" --include="Makefile" --include="*.mk" .
```



### 3.4 解决 GCC 版本过高问题

Ubuntu 24.04 默认 GCC 13，而 CUDA 11.8 要求 GCC ≤ 11。安装并切换默认编译器：

```bash
# 安装 GCC-11
sudo apt install gcc-11 g++-11

# 配置系统默认编译器为 GCC-11
sudo update-alternatives --install /usr/bin/gcc gcc /usr/bin/gcc-11 100
sudo update-alternatives --install /usr/bin/g++ g++ /usr/bin/g++-11 100
# （若有其他版本，可设置较低优先级，例如 gcc-13 设为 50）
```

或者手动选择/查看：

```bash
# 手动选择 gcc-11（如果系统有多个候选）
sudo update-alternatives --config gcc
sudo update-alternatives --config g++
```

验证切换结果：

```bash
# 验证是否切换成功，应显示 11.x.x
gcc --version
g++ --version
```



### 3.5 编译单个测试（以 `backprop` 为例）

由于顶层 Makefile 可能缺失，推荐直接进入测试子目录编译：

```bash
cd ~/Project/gpu-rodinia-3.1/cuda/backprop
make clean
make
```

编译成功后生成可执行文件 `backprop`。



### 注：编译完成后恢复系统默认 GCC（可选）

如果需要恢复系统原 GCC 版本（例如你日常开发需要用新版本），执行：

```bash
sudo update-alternatives --config gcc
# 选择之前的高版本编号（如 gcc-13）

sudo update-alternatives --config g++
# 同样选择对应的高版本
```





------

## 🚀 第四部分：运行 GPGPU-Sim 模拟

### 4.1 准备模拟环境

每次打开新终端运行前，首先加载 GPGPU-Sim 环境：

```bash
cd ~/Project/gpgpu-sim_distribution
export CUDA_INSTALL_PATH=/usr/local/cuda-11.8
source setup_environment release
```

指定配置文件路径：

```bash
export GPGPUSIM_CONFIG=$PWD/gpgpusim.config
```



### 4.2 运行 `backprop` 测试

```bash
cd ~/Project/gpu-rodinia-3.1/cuda/backprop
./backprop 65536
```



若提示找不到 `gpgpusim.config`，直接将配置文件复制到当前目录：

```bash
cp ~/Project/gpgpu-sim_distribution/gpgpusim.config .
cp ~/Project/gpgpu-sim_distribution/accelwattch*.xml .
cp ~/Project/gpgpu-sim_distribution/config_volta*.icnt .
```

然后直接运行 `./backprop 65536`。



### 4.3 验证模拟成功

程序运行结束后，应输出类似以下内容：

```bash
GPGPU-Sim: *** exit detected ***
GPGPU-Sim: Simulation done.
gpu_tot_sim_cycle = ...
gpu_tot_ipc = ...
```

若看到这些统计信息，说明模拟器已正确劫持 CUDA 调用，GV100 配置生效。



------

## ⚠️ 第五部分：遇到的问题及解决方案汇总

| 序号 | 问题描述                                                     | 原因分析                                         | 解决方案                                                     |
| ---- | ------------------------------------------------------------ | ------------------------------------------------ | ------------------------------------------------------------ |
| 1    | `setup_environment release` 报错 `CUDA_INSTALL_PATH` 未设置  | 脚本未检测到系统 CUDA 路径                       | 手动 `export CUDA_INSTALL_PATH=/usr/local/cuda-11.8`         |
| 2    | 编译 GPGPU-Sim 时出现 `undefined reference to nppGetLibVersion` | CUDA 12.x 移除旧版 NPP 库符号                    | **安装并使用 CUDA 11.8** 进行编译                            |
| 3    | `cp configs/tested-cfgs/SM7_GV100/*` 报错 `Not a directory`  | 当前目录已存在 `gpgpusim.config` 文件，cp 误判   | 先删除或强制覆盖：`cp -fv configs/tested-cfgs/SM7_GV100/* .` |
| 4    | Rodinia 编译时 `nvcc fatal : Value 'sm_13' is not defined for option 'gpu-architecture'` | CUDA 11.x 已移除计算能力 1.x 架构支持            | 批量替换 Makefile 中的 `sm_13` 为 `sm_35`                    |
| 5    | Rodinia 编译时 `#error -- unsupported GNU version! gcc versions later than 11 are not supported!` | Ubuntu 24.04 默认 GCC 13 超过 CUDA 11.8 支持上限 | 安装 GCC-11 并通过 `update-alternatives` 切换为系统默认      |
| 6    | `make clean` 报错 `can't cd to .../bin/linux/cuda`           | 目录结构不完整                                   | 手动创建目录：`mkdir -p bin/linux/cuda`                      |
| 7    | 运行 `backprop` 时找不到 `gpgpusim.config`                   | 模拟器默认在当前目录查找配置文件                 | 方法一：`export GPGPUSIM_CONFIG=/path/to/gpgpusim.config`；方法二：复制配置文件到测试目录 |
| 8    | 运行测试瞬间结束，无 GPGPU-Sim 统计信息                      | 环境变量未正确加载，程序运行在真实 GPU 上        | 运行前务必执行 `source setup_environment release` 并检查 `echo $GPGPUSIM_ROOT` |
| 9    | 程序报 CUDA 错误或段错误                                     | 模拟器参数与测试不兼容或资源不足                 | 调小测试输入参数，或修改 `gpgpusim.config` 中 `-gpu_max_cycle` 值 |

------

## ✅ 第六部分：运行结果

通过上述步骤，我们成功在 Ubuntu 24.04 上搭建了 GPGPU-Sim + GV100 的模拟环境，并跑通了 Rodinia 的 `backprop` 基准测试。模拟器输出的 IPC、缓存命中率、互联网络冲突率等统计数据，可用于进一步的 GPU 体系结构研究。

后续可尝试运行 Rodinia 中其他测试程序（如 `hotspot`、`lavaMD`），只需重复 **第三、四部分** 中的编译与运行步骤即可。



运行结果及总结如下：

```bash
----------------------------Interconnect-DETAILS--------------------------------
Req_Network_injected_packets_num = 978942
Req_Network_cycles = 53473
Req_Network_injected_packets_per_cycle =      18.3072
Req_Network_conflicts_per_cycle =      12.2125
Req_Network_conflicts_per_cycle_util =      16.3304
Req_Bank_Level_Parallism =      24.4803
Req_Network_in_buffer_full_per_cycle =       0.0000
Req_Network_in_buffer_avg_util =      14.5179
Req_Network_out_buffer_full_per_cycle =       0.0000
Req_Network_out_buffer_avg_util =       6.3974

Reply_Network_injected_packets_num = 978942
Reply_Network_cycles = 53473
Reply_Network_injected_packets_per_cycle =       18.3072
Reply_Network_conflicts_per_cycle =       11.2127
Reply_Network_conflicts_per_cycle_util =      15.0549
Reply_Bank_Level_Parallism =      24.5805
Reply_Network_in_buffer_full_per_cycle =       0.0000
Reply_Network_in_buffer_avg_util =       6.5687
Reply_Network_out_buffer_full_per_cycle =       0.0000
Reply_Network_out_buffer_avg_util =       0.2288
----------------------------END-of-Interconnect-DETAILS-------------------------


gpgpu_simulation_time = 0 days, 0 hrs, 5 min, 32 sec (332 sec)
gpgpu_simulation_rate = 405258 (inst/sec)
gpgpu_simulation_rate = 161 (cycle/sec)
gpgpu_silicon_slowdown = 8987577x
Training done
GPGPU-Sim: *** exit detected ***
```



以下是针对你运行 `backprop` 后输出的 **Interconnect-DETAILS** 及**模拟总结**数据的简要分析：

### 📊 互联网络分析（Interconnect-DETAILS）

| 指标                           | 请求网络 (Req) | 响应网络 (Reply) | 分析解读                                                     |
| ------------------------------ | -------------- | ---------------- | ------------------------------------------------------------ |
| **Injected packets per cycle** | 18.3           | 18.3             | 每周期注入数据包数。此值较高，表明 `backprop` 对显存**访问请求密集**，符合神经网络训练程序中权重和误差频繁读写的特征。 |
| **Conflicts per cycle (util)** | **16.33%**     | **15.05%**       | 路由器冲突利用率。约 **15–16%** 的时间数据包在片上网络发生仲裁冲突。该值处于**中等水平**，说明网络有一定竞争但尚未成为严重瓶颈。 |
| **Bank Level Parallelism**     | **24.48**      | **24.58**        | DRAM 存储体级并行度。GV100 配置下该值较高，说明访存请求被**均匀分散到多个内存通道**，显存带宽利用充分。 |
| **Buffer Avg Util**            | 14.5% / 6.4%   | 6.6% / 0.2%      | 输入/输出缓冲区平均占用率。均低于 15%，且无 `full` 事件，说明**网络未出现拥塞堵塞**，数据流动顺畅。 |

> ✅ **小结**：在 GV100 配置下，`backprop` 的片上互联网络表现**健康高效**，冲突率适中，存储体并行度高，未产生明显网络瓶颈。

------

### ⏱️ 模拟效率分析

| 指标         | 数值               | 含义                                                         |
| ------------ | ------------------ | ------------------------------------------------------------ |
| **模拟时间** | 5 分 32 秒 (332 s) | 运行一次 `backprop` 模拟所花费的墙上时间。                   |
| **模拟速率** | 405,258 指令/秒    | 模拟器处理指令的速度。                                       |
| **周期速率** | 161 周期/秒        | 模拟器推进 GPU 时钟周期的速度。                              |
| **硅减速比** | **8,987,577×**     | 模拟时间相对于真实硬件运行时间的倍数。约 **900 万倍减速**是 GPGPU-Sim 详细周期级模拟的**正常表现**，同时也证实程序确实在模拟器中运行（而非真实 GPU）。 |

## 

## 🧪 第七部分：运行其它测试

环境配置完成后，后续运行任意 Rodinia 测试仅需 **加载环境 → 编译 → 运行** 三步。

### 🚀 通用操作流程

1. 加载 GPGPU-Sim 环境（每次新开终端必须执行）

```bash
cd ~/Project/gpgpu-sim_distribution
export CUDA_INSTALL_PATH=/usr/local/cuda-11.8
source setup_environment release
export GPGPUSIM_CONFIG=$PWD/gpgpusim.config  # 可选，或直接将配置文件复制到测试目录
```

> **提示**：可将上述命令写入脚本 `~/load_gpgpusim.sh`，之后只需 `source ~/load_gpgpusim.sh`。

2. 进入目标测试目录并编译

```bash
cd ~/Project/gpu-rodinia-3.1/cuda/<测试名称>
make clean
make
```

3. 运行模拟

```bash
./<可执行文件> [参数]
```



以 `hotspot` 为例：

```bash
# 1. 加载环境（如上）
cd ~/Project/gpgpu-sim_distribution
source ~/load_gpgpusim.sh   # 若已创建脚本

# 2. 进入测试目录并编译
cd ~/Project/gpu-rodinia-3.1/cuda/hotspot
make clean
make

# 3. 运行模拟
./hotspot 512 2 2   # 参数可调整
```



**常用测试运行命令参考：**

| 测试程序     | 目录              | 典型运行命令                  |
| ------------ | ----------------- | ----------------------------- |
| `backprop`   | `cuda/backprop`   | `./backprop 65536`            |
| `pathfinder` | `cuda/pathfinder` | `./pathfinder 100000 100 20`  |
| `lavaMD`     | `cuda/lavaMD`     | `./lavaMD -boxes1d 10`        |
| `hotspot`    | `cuda/hotspot`    | `./hotspot 512 2 2`           |
| `bfs`        | `cuda/bfs`        | `./bfs <graph_file>`          |
| `gaussian`   | `cuda/gaussian`   | `./gaussian -f <matrix_file>` |



### ⚠️ 注意事项

- **配置文件**：若运行时提示找不到 `gpgpusim.config`，将 `~/Project/gpgpu-sim_distribution/` 下的 `gpgpusim.config`、`accelwattch*.xml`、`config_volta*.icnt` 复制到测试目录。
- **GCC 版本**：若后续系统更新导致 GCC 默认版本恢复，需重新执行 `sudo update-alternatives --config gcc` 切换回 GCC-11。
- **批量测试**：可写脚本循环运行不同参数，收集 IPC、周期数等统计数据。