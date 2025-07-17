package rv32isc

import chisel3._
import chisel3.util._

import bundles._
import config.Configs._
import config.OoOParams._

class AluRS extends Module {
  val io = IO(new AluRSIO)

  // === 保留栈 ===
  val entries   = Reg(Vec(RS_ALU_SIZE, new AluIssueEntry))
  val validVec  = RegInit(VecInit(Seq.fill(RS_ALU_SIZE)(false.B)))

  // === 多条入队 ===
  val enqVec        = io.in.enq
  val freeIdxVec    = Wire(Vec(ISSUE_WIDTH, UInt(log2Ceil(RS_ALU_SIZE).W)))
  val freeValidVec  = Wire(Vec(ISSUE_WIDTH, Bool()))
  val tempValidVec  = WireInit(VecInit(validVec.map(v => !v))) // 正确

  // 分配空槽
  for (w <- 0 until ISSUE_WIDTH) {
    freeIdxVec(w) := PriorityEncoder(tempValidVec)
    freeValidVec(w) := tempValidVec.reduce(_ || _)
    when(freeValidVec(w)) {
      tempValidVec(freeIdxVec(w)) := false.B // 不可重复
    }
  }

  // 入队逻辑
  for (w <- 0 until ISSUE_WIDTH) {
    enqVec(w).ready := freeValidVec(w)

    when(enqVec(w).valid && freeValidVec(w)) {
      val idx = freeIdxVec(w)
      entries(idx) := enqVec(w).bits
      entries(idx).rs1Ready := !enqVec(w).bits.useRs1
      entries(idx).rs2Ready := !enqVec(w).bits.useRs2
      validVec(idx) := true.B
    }
  }

  // === 前馈广播更新 ===
  for (i <- 0 until RS_ALU_SIZE) {
    when(validVec(i)) {
      for (bp <- io.in.bypass) {
        when(entries(i).useRs1 && !entries(i).rs1Ready && bp.valid && bp.phyDest === entries(i).phyRs1) {
          entries(i).rs1Ready := true.B
        }
        when(entries(i).useRs2 && !entries(i).rs2Ready && bp.valid && bp.phyDest === entries(i).phyRs2) {
          entries(i).rs2Ready := true.B
        }
      }
    }
  }

  // === 多路发射 ===
  val issueVec = Wire(Vec(RS_ALU_SIZE, Bool()))
  for (i <- 0 until RS_ALU_SIZE) {
    val e = entries(i)
    issueVec(i) := validVec(i) &&
      (!e.useRs1 || e.rs1Ready) &&
      (!e.useRs2 || e.rs2Ready)
  }

  val issued      = Wire(Vec(ALU_UNITS, Bool()))
  val issuedIdx   = Wire(Vec(ALU_UNITS, UInt(log2Ceil(RS_ALU_SIZE).W)))
  val mask        = WireInit(VecInit(Seq.fill(RS_ALU_SIZE)(true.B)))

  // 记录是否使用过某槽位
  for (u <- 0 until ALU_UNITS) {
    val maskedIssueVec = (0 until RS_ALU_SIZE).map(i => issueVec(i) && mask(i))
    val valid = maskedIssueVec.reduce(_ || _)
    val idx   = PriorityEncoder(maskedIssueVec)

    issued(u) := valid && io.in.aluReady(u)
    issuedIdx(u) := idx

    io.out.issue(u).valid := issued(u)
    io.out.issue(u).bits  := entries(idx)

    // 更新 mask，防止重复发射
    when(issued(u)) {
      mask(idx) := false.B
    }
  }

  // === 发射后清空 ===
  for (u <- 0 until ALU_UNITS) {
    when(io.out.issue(u).fire) {
      validVec(issuedIdx(u)) := false.B
    }
  }
}

