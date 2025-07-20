package rv32isc

import chisel3._
import chisel3.util._
import bundles._

import config.Configs._
import config.OoOParams._

class AluRS extends Module {
  val io = IO(new AluRSIO)

  // === 保留站表项结构 ===
  val entries = RegInit(VecInit(Seq.fill(RS_ALU_SIZE)(0.U.asTypeOf(Valid(new AluIssueEntry)))))
  
  // === 有效位与空槽判断 ===
  val validVec = Wire(Vec(RS_ALU_SIZE, Bool()))
  val freeVec  = Wire(Vec(RS_ALU_SIZE, Bool()))
  for (i <- 0 until RS_ALU_SIZE) {
    validVec(i) := entries(i).valid
    freeVec(i)  := !entries(i).valid
  }

  // === 剩余槽位数量输出 ===
  val freeCount = PopCount(freeVec)
  io.out.freeEntryCount := Mux(freeCount > ISSUE_WIDTH.U, ISSUE_WIDTH.U, freeCount)

  // === 入队逻辑 ===
  val enqVec = io.in.enq

  for (i <- 0 until ISSUE_WIDTH) {
    val enq = enqVec(i)
    val inserted = WireInit(false.B)
    for (j <- 0 until RS_ALU_SIZE) {
      when(enq.valid && !validVec(j) && !inserted && !io.in.rollback.valid) {
        entries(j).valid := true.B
        entries(j).bits  := enq.bits
        inserted := true.B
      }
    }
  }

  // === 暂未实现发射/回滚逻辑 ===
  // 下一步我们会处理乱序发射与回滚清除
}
