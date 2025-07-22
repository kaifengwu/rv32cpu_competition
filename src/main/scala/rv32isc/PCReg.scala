package rv32isc
import chisel3._
import chisel3.util._

import bundles._
import config.Configs._
import config.InstructionConstants._

class PCReg extends Module {
  val io = IO(new PCRegIO)

  val pc = RegInit(START_ADDR.U(ADDR_WIDTH.W))

  // PC 更新逻辑：redirect > 顺序
  when(io.in.redirect.valid) {
    pc := io.in.redirect.bits
  }.elsewhen(io.in.predict.valid && !io.in.redirect.valid){
    pc := io.in.predict.bits
  }.elsewhen(!io.in.stall) {
    pc := pc + (4 * FETCH_WIDTH).U
  }

  io.out.pcBase := pc

  // 输出 fetch 宽度的地址向量
  for (i <- 0 until FETCH_WIDTH) {
    io.out.pcVec(i) := pc + (i.U << 2)
  }
}
