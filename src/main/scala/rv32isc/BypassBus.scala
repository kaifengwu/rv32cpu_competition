package rv32isc

import chisel3._
import chisel3.util._

import config.OoOParams._
import config.Configs._
import bundles._

class BypassUnit extends Module {
  val io = IO(new BypassUnitIO)

  // 暂时直接将 in 端口数据原样输出给 out（直通）
  for (i <- 0 until NUM_BYPASS_PORTS) {
    io.out(i).valid   := io.in(i).valid
    io.out(i).reg.phyDest := io.in(i).reg.phyDest
    io.out(i).data    := io.in(i).data
    io.out(i).reg.robIdx  := io.in(i).reg.robIdx
  }
}
