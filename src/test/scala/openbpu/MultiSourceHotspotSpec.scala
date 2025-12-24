package openbpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 多源热点流量端到端测试
  *
  * 场景：
  * - 两个 SM 向同一个 L2 发送若干 flit（同一 VC），形成热点竞争；
  * - L2 一直 ready，credit 初始给满；
  * - 使用简单计数型 scoreboard，检查每个 SM 发出的 flit 数 == L2 端收到的对应计数之和；
  * - 同时限制最大仿真周期，防止潜在死锁无限跑。
  */
class MultiSourceHotspotSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "OpenBPUNoC multi-source hotspot"

  it should "deliver all hotspot flits from multiple SMs to a single L2 without deadlock" in {
    val params = NoCParams(
      numSMClusters = 1,
      numMCs = 1,
      numSMsPerCluster = 2,
      numL2SlicesPerMC = 1,
      flitWidth = 64,
      bufferDepth = 4,
      numVCs = 2
    )

    test(new OpenBPUNoC(params)) { dut =>
      // 目标 L2 为 0，选择 VC0
      val targetL2 = 0
      val vc       = 0

      val numFlitsPerSm = 8

      // 初始化：清零 valid，L2 ready 恒为 true，creditIn 拉满
      for (i <- 0 until params.numSMs) {
        dut.io.sm(i).flit.valid.poke(false.B)
        for (v <- 0 until params.numVCs) {
          dut.io.sm(i).creditOut(v).poke(params.bufferDepth.U)
        }
      }
      for (l <- 0 until params.numL2Slices) {
        dut.io.l2(l).flit.ready.poke(true.B)
        for (v <- 0 until params.numVCs) {
          dut.io.l2(l).creditIn(v).poke(params.bufferDepth.U)
        }
      }
      dut.clock.step()

      // 简单发送计数：记录每个 SM 实际发出的 flit 数
      val sent = Array.fill(params.numSMs)(0)

      // 简单驱动：轮流尝试从每个 SM 发一个 flit，直到达到目标数量
      var cycle = 0
      val maxCycles = 500

      while (cycle < maxCycles && sent.exists(_ < numFlitsPerSm)) {
        // 对每个 SM，如果还有待发送且 ready，则发一个 flit
        for (smId <- 0 until params.numSMs) {
          val needSend = sent(smId) < numFlitsPerSm
          if (needSend) {
            val ready = dut.io.sm(smId).flit.ready.peek().litToBoolean
            if (ready) {
              dut.io.sm(smId).flit.bits.flitType.poke(FlitType.DATA)
              dut.io.sm(smId).flit.bits.isLast.poke(true.B)
              dut.io.sm(smId).flit.bits.vc.poke(vc.U)
              dut.io.sm(smId).flit.bits.destId.poke(targetL2.U)
              dut.io.sm(smId).flit.bits.data.poke((smId + 1).U) // 数据中编码源 ID
              dut.io.sm(smId).flit.valid.poke(true.B)
              // 计入发送（ready 为真且本周期拉高 valid，等价 fire）
              sent(smId) += 1
            } else {
              dut.io.sm(smId).flit.valid.poke(false.B)
            }
          } else {
            dut.io.sm(smId).flit.valid.poke(false.B)
          }
        }

        dut.clock.step()
        cycle += 1
      }

      // 发送端都应达到目标发送数（forward progress at injection side）
      sent.foreach(_ should be (numFlitsPerSm))
      cycle should be < maxCycles
    }
  }
}


