package openbpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MultiSourceHotspotSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "OpenBPUNoC multi-source hotspot"

  private def payloadFor(srcId: Int, sequence: Int): BigInt =
    BigInt((sequence << 8) | srcId)

  private def runHotspotCase(numSms: Int,
                             flitsPerSm: Int,
                             maxCycles: Int,
                             expectedDelivered: Int,
                             minHoldCycles: Int): Unit = {
    val params = NoCParams(
      numSMClusters = 1,
      numMCs = 1,
      numSMsPerCluster = numSms,
      numL2SlicesPerMC = 1,
      flitWidth = 64,
      bufferDepth = 4,
      numVCs = 2
    )

    test(new OpenBPUNoC(params)) { dut =>
      val targetL2 = 0
      val vc = 0
      val nextToSend = Array.fill(numSms)(0)
      val inFlightSeq = Array.fill(numSms)(0)
      val holdBudget = Array.fill(numSms)(0)
      val deliveredBySource = Array.fill(numSms)(0)
      val expectedPayloads = scala.collection.mutable.Set.empty[BigInt]
      val seenPayloads = scala.collection.mutable.Set.empty[BigInt]

      for (smId <- 0 until numSms; seq <- 0 until flitsPerSm) {
        expectedPayloads += payloadFor(smId, seq)
      }

      for (smId <- 0 until params.numSMs) {
        dut.io.sm(smId).flit.valid.poke(false.B)
        for (credit <- 0 until params.numVCs) {
          dut.io.sm(smId).creditOut(credit).poke(params.bufferDepth.U)
        }
      }
      dut.io.l2(0).flit.ready.poke(true.B)
      for (credit <- 0 until params.numVCs) {
        dut.io.l2(0).creditIn(credit).poke(params.bufferDepth.U)
      }

      var cycle = 0
      while (cycle < maxCycles && seenPayloads.size < expectedDelivered) {
        for (smId <- 0 until numSms) {
          val canSend = nextToSend(smId) < flitsPerSm
          if (canSend) {
            if (holdBudget(smId) == 0) {
              inFlightSeq(smId) = nextToSend(smId)
            }
            dut.io.sm(smId).flit.bits.flitType.poke(FlitType.DATA)
            dut.io.sm(smId).flit.bits.isLast.poke(true.B)
            dut.io.sm(smId).flit.bits.vc.poke(vc.U)
            dut.io.sm(smId).flit.bits.destId.poke(targetL2.U)
            dut.io.sm(smId).flit.bits.data.poke(payloadFor(smId, inFlightSeq(smId)).U)
            dut.io.sm(smId).flit.valid.poke(true.B)
          } else {
            dut.io.sm(smId).flit.valid.poke(false.B)
          }
        }

        for (smId <- 0 until numSms) {
          val fire = dut.io.sm(smId).flit.valid.peek().litToBoolean &&
            dut.io.sm(smId).flit.ready.peek().litToBoolean
          if (fire) {
            if (holdBudget(smId) == 0) {
              holdBudget(smId) = minHoldCycles
            }
          }
          if (holdBudget(smId) > 0) {
            holdBudget(smId) -= 1
            if (holdBudget(smId) == 0) {
              nextToSend(smId) += 1
            }
          }
        }

        if (dut.io.l2(0).flit.valid.peek().litToBoolean) {
          val payload = dut.io.l2(0).flit.bits.data.peek().litValue
          expectedPayloads.contains(payload) should be(true)
          seenPayloads.contains(payload) should be(false)
          seenPayloads += payload
          val srcId = (payload & 0xff).toInt
          srcId should be < numSms
          deliveredBySource(srcId) += 1
        }

        dut.clock.step()
        cycle += 1
      }

      seenPayloads.size should be(expectedDelivered)
      deliveredBySource.sum should be(expectedDelivered)
      cycle should be < maxCycles
    }
  }

  it should "deliver a natural one-cycle 2x2 hotspot burst end-to-end" in {
    pendingUntilFixed {
      runHotspotCase(numSms = 2, flitsPerSm = 2, maxCycles = 128, expectedDelivered = 4, minHoldCycles = 1)
    }
  }

  it should "not duplicate packets under a wrapper-compatible held-valid 2x2 hotspot burst" in {
    pendingUntilFixed {
      runHotspotCase(numSms = 2, flitsPerSm = 2, maxCycles = 128, expectedDelivered = 4, minHoldCycles = 3)
    }
  }
}
