package rv32isc

import chisel3._
import chisel3.util._

import bundles._
import config.OoOParams._

class RenameDispatchReg extends Module {
  val io = IO(new RenameDispatchRegIO)

  val renameVecReg   = RegInit(0.U.asTypeOf(new RenameBundle))

  when (io.in.flush) {
    renameVecReg := 0.U.asTypeOf(new RenameBundle)
  }.elsewhen (!io.in.stall) {
    renameVecReg := io.in.renameVec
  }.otherwise{
    renameVecReg := renameVecReg
   }
  io.out.renameVec := renameVecReg
}
