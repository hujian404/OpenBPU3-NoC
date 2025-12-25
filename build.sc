// build.sc — Mill 0.11.x + Chisel 6 (FIXED)

import mill._
import mill.scalalib._
import mill.scalalib.TestModule.ScalaTest

object MyNoC extends SbtModule { m =>

  override def millSourcePath = os.pwd

  override def scalaVersion = "2.13.12"

  // 有多个 generator(openbpu.NoCGenerator, openbpu.OpenBPUNoCGenerator),指定一个
  override def mainClass = Some("openbpu.NoCGenerator")

  override def scalacOptions = Seq(
    "-language:reflectiveCalls",
    "-deprecation",
    "-feature",
    "-Xcheckinit"
  )

  override def ivyDeps = Agg(
    ivy"org.chipsalliance::chisel:6.2.0"
  )

  override def scalacPluginIvyDeps = Agg(
    ivy"org.chipsalliance:::chisel-plugin:6.2.0"
  )

  object test extends SbtModuleTests with ScalaTest {
    override def ivyDeps = m.ivyDeps() ++ Agg(
      ivy"org.scalatest::scalatest::3.2.16",
      ivy"edu.berkeley.cs::chiseltest::6.0.0"
    )
  }
}
