# GPGPU-Sim 集成分支说明

本文档说明 `codex/gpgpu-sim-integrated` 分支的用途。

## 分支目标

这个分支把 `gpgpu-sim/` 从 Git submodule 转换成了仓库内直接跟踪的源码快照。

这样做的目的有两个：

- 避免 `gpgpu-sim` 本地修改只停留在脏子模块工作树中
- 让远端服务器或新机器在 clone 本分支后，直接拿到已经集成 OpenBPU backend 的
  `gpgpu-sim` 源码

## 与 `main` 分支的区别

`main` 分支当前采用的是：

- `gpgpu-sim/` 作为 submodule
- 通过 `patches/gpgpu-sim-openbpu-integration.patch`
  和 `scripts/apply_gpgpu_sim_patch.sh` 自动补丁化集成

本分支采用的是：

- `gpgpu-sim/` 直接纳入版本管理
- 分支内保存一份已集成 OpenBPU backend 的源码快照

## 适用场景

推荐在这些情况下使用本分支：

- 需要在服务器上快速 clone 后直接构建
- 需要保留某一时刻 `gpgpu-sim` 改动的完整快照
- 需要减少 submodule / patch 应用带来的额外步骤

## clone 命令

```bash
git clone -b codex/gpgpu-sim-integrated https://gitee.com/OpenBPU/openbpu3-noc.git
cd openbpu3-noc
```

由于本分支内 `gpgpu-sim/` 已经是普通目录，因此：

- 不需要再对 `gpgpu-sim` 执行补丁恢复
- 不需要为 `gpgpu-sim` 单独初始化 submodule

但 Rodinia 仍可能保留为 submodule，因此首次使用时仍建议执行：

```bash
git submodule update --init --recursive
```

## 构建建议

推荐直接使用仓库内统一脚本：

```bash
export CUDA_INSTALL_PATH=/usr/local/cuda-11.8
export PATH=$CUDA_INSTALL_PATH/bin:/usr/local/bin:$PATH
export LD_LIBRARY_PATH=$CUDA_INSTALL_PATH/lib64:$LD_LIBRARY_PATH
NOC_FORCE_BUILD_NOC=1 ./scripts/build_sim.sh local
```

## 维护建议

这个分支更适合作为“集成快照分支”，而不是长期替代 `main` 分支。

建议维护方式：

- `main` 继续保留 submodule + patch 的上游友好结构
- 本分支用于保存可直接部署的完整集成状态
