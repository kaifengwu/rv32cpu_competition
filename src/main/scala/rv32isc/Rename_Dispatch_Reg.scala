package rv32isc

import chisel3._
import chisel3.util._

import bundles._
import config.OoOParams._

class RenameDispatchReg extends Module {
  val io = IO(new RenameDispatchRegIO)

  val renameVecReg   = RegInit(VecInit(Seq.fill(ISSUE_WIDTH)(0.U.asTypeOf(new RenameBundle))))
  val isRet = RegInit(Bool())

  when (io.in.flush) {
    renameVecReg := VecInit(Seq.fill(ISSUE_WIDTH)(0.U.asTypeOf(new RenameBundle)))
    isRet := false.B
  } .elsewhen (!io.in.stall) {
    renameVecReg := io.in.renameVec
    isRet := io.in.isRet
  }

  io.out.renameVec := renameVecReg
  io.out.isRet := isRet
}
