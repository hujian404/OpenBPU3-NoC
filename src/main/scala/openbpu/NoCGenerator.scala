package openbpu

import chisel3._
import _root_.circt.stage.ChiselStage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

// 简单的NoC生成器主程序
object NoCGenerator {
  private def emitMetadata(params: NoCParams): Unit = {
    val outputDir = Paths.get("generated")
    Files.createDirectories(outputDir)
    val metadata =
      s"""|NOC_MODULE_NAME=OpenBPUNoC
          |NOC_NUM_INPUTS=${params.numSMs}
          |NOC_NUM_OUTPUTS=${params.numL2Slices}
          |NOC_DEST_BITS=${params.destWidth}
          |NOC_VC_BITS=${params.vcWidth}
          |NOC_DATA_BITS=${params.flitDataWidth}
          |NOC_PACKET_BITS=${params.flitWidth}
          |NOC_CREDIT_WIDTH=${params.creditWidth}
          |""".stripMargin
    Files.write(
      outputDir.resolve("openbpu_noc_meta.env"),
      metadata.getBytes(StandardCharsets.UTF_8)
    )
  }

  def main(args: Array[String]): Unit = {
    println("Generating OpenBPU NoC Verilog code...")
    
    // 使用默认参数配置
    val params = DefaultNoCParams
    
    println(s"Configuration parameters:")
    println(s"- Number of SM clusters: ${params.numSMClusters}")
    println(s"- Number of MC routers: ${params.numMCs}")
    println(s"- SMs per cluster: ${params.numSMsPerCluster}")
    println(s"- L2 slices per MC: ${params.numL2SlicesPerMC}")
    println(s"- Total SMs: ${params.numSMs}")
    println(s"- Total L2 slices: ${params.numL2Slices}")
    println(s"- Flit width: ${params.flitWidth} bits")
    println(s"- Buffer depth: ${params.bufferDepth} flits")
    println(s"- Number of VCs: ${params.numVCs}")
    
    // 生成Verilog代码
    ChiselStage.emitSystemVerilogFile(
      new OpenBPUNoC(params),
      args = args ++ Array("--target-dir", "generated")
    )
    emitMetadata(params)
    
    println("\nNoC Verilog generation completed!")
    println("Generated files are in the 'generated' directory.")
    println("Generated NoC metadata: generated/openbpu_noc_meta.env")
  }
}
