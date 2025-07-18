package rv32isc
import chisel3._
import chisel3.util._
import bundles._
import config.Configs._

class ALU_WB_Reg extends Module {
  val io = IO(new Bundle {
    val in    = Input(new ALU_OUT)    // ALU阶段输出
    val out   = Output(new ALU_OUT)   // WB阶段输入
    val stall = Input(Bool())        // 阻塞信号
    val flush = Input(Bool())        // 冲刷信号
  })

  val reg = RegInit(0.U.asTypeOf(new ALU_OUT))

  when(io.flush) {
    reg := 0.U.asTypeOf(new ALU_OUT)
  }.elsewhen(!io.stall) {
    reg := io.in
  }

  io.out := reg
}
