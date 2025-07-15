package rv32isc

import chisel3._
import chisel3.util._

import config.OoOParams._
import bundles._

class RAT extends Module {
  val io = IO(new RATIO)

  val table = RegInit(VecInit((0 until ARCH_REG_NUM).map(_.U(PHYS_REG_IDX_WIDTH.W))))

  // 查询 rs1/rs2
  for (i <- 0 until ISSUE_WIDTH) {
    io.out.phyRs1(i) := table(io.in.logicRs1(i))
    io.out.phyRs2(i) := table(io.in.logicRs2(i))
    io.out.oldPhyRd(i) := table(io.in.logicRd(i))
  }

  // 写入新映射
  for (i <- 0 until ISSUE_WIDTH) {
    when(io.in.wen(i) && (io.in.logicRd(i) =/= 0.U)) {
      table(io.in.logicRd(i)) := io.in.phyRd(i)
    }
  }
}
