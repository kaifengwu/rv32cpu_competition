import chisel3._
import chisel3.util._
import chisel3.stage.ChiselStage
import rv32isc._

object Main extends App {
  // 生成 SystemVerilog 代码

  val verilog = (new ChiselStage).emitVerilog(new SIM_CPU)

  // 打印到控制台
//    println(verilog)

  // 保存到文件
  import java.io.{File, PrintWriter}
}
