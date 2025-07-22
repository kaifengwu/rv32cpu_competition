package rv32isc

import chisel3._
import chisel3.util._
import bundles._

import config.Configs._
import config.OoOParams._
import RsUtils._

class AluRS extends Module {
  val io = IO(new AluRSIO)

  // === 保留站表项结构 ===
  val entries = RegInit(VecInit(Seq.fill(RS_ALU_SIZE)(0.U.asTypeOf(Valid(new AluIssueEntry)))))
  
  // === 有效位与空槽判断 ===
  val validVec = Wire(Vec(RS_ALU_SIZE, Bool()))

  val freeVec = Wire(Vec(RS_ALU_SIZE, Bool()))
  for (i <- 0 until RS_ALU_SIZE) {
    freeVec(i) := !entries(i).valid
  }

  val enqVec = io.in.enq
  val freeIdxVec = getNFreeSlots(freeVec, ISSUE_WIDTH)

  for (i <- 0 until ISSUE_WIDTH) {
    val enq = enqVec(i)
    val idx = freeIdxVec(i)
    when(enq.valid && !io.in.rollback.valid) {
      entries(idx).valid := true.B
      entries(idx).bits  := enq.bits
    }
  }

  // === 发射准备 ===
  val fireVec         = WireDefault(VecInit(Seq.fill(RS_ALU_SIZE)(false.B)))
  val selectedEntries = Wire(Vec(ALU_UNITS, UInt(log2Ceil(RS_ALU_SIZE).W)))
  val selectedValids  = WireDefault(VecInit(Seq.fill(ALU_UNITS)(false.B)))


  // === 判断每条是否 ready，可以被发射 ===
  for (i <- 0 until RS_ALU_SIZE) {
    val entry = entries(i)
    val bypassMatchRs1 = io.in.bypass.map(bp => bp.valid && entry.bits.useRs1 && !entry.bits.rs1Ready && bp.reg.phyDest === entry.bits.phyRs1).reduce(_ || _)
    val bypassMatchRs2 = io.in.bypass.map(bp => bp.valid && entry.bits.useRs2 && !entry.bits.rs2Ready && bp.reg.phyDest === entry.bits.phyRs2).reduce(_ || _)

    val rs1Ok = !entry.bits.useRs1 || entry.bits.rs1Ready || bypassMatchRs1
    val rs2Ok = !entry.bits.useRs2 || entry.bits.rs2Ready || bypassMatchRs2

    // === 若 rollback.valid，则禁止一切发射 ===
    fireVec(i) := entry.valid && rs1Ok && rs2Ok && !io.in.rollback.valid
  }


  // === ALU 发射选择 ===
  val fireMask = WireInit(VecInit(Seq.fill(ALU_UNITS)(0.U(RS_ALU_SIZE.W))))

  // 转换为 UInt 一次，提高并行性
  val fireVecUInt = fireVec.asUInt

  // 主循环：每个 ALU 分配一个就绪的槽位
  for (i <- 0 until ALU_UNITS) {
    // 累积之前已经分配出去的 fireMask（防止重复选择）
    val usedMask = if (i == 0) 0.U else fireMask.slice(0, i).reduce(_ | _)
    val maskedFireVec = fireVecUInt & ~usedMask

    // 编码
    val oh  = PriorityEncoderOH(maskedFireVec)
    val idx = PriorityEncoder(maskedFireVec)

    fireMask(i)        := oh
    selectedEntries(i) := idx
    selectedValids(i)  := maskedFireVec.orR && !io.in.rollback.valid
  }


  // === 发射给 ALU 并更新 RS ===
  for (u <- 0 until ALU_UNITS) {
    val idx   = selectedEntries(u)
    val entry = entries(idx)

    val rs1Bypass = WireDefault(false.B)
    val rs2Bypass = WireDefault(false.B)
    val rs1Value  = WireDefault(entry.bits.rs1data)
    val rs2Value  = WireDefault(entry.bits.rs2data)

    for (j <- 0 until NUM_BYPASS_PORTS) {
      val bp = io.in.bypass(j)
      when(bp.valid && entry.bits.useRs1 && !entry.bits.rs1Ready && bp.reg.phyDest=== entry.bits.phyRs1) {
        rs1Bypass := true.B
        rs1Value := bp.data
      }
      when(bp.valid && entry.bits.useRs2 && !entry.bits.rs2Ready && bp.reg.phyDest === entry.bits.phyRs2) {
        rs2Bypass := true.B
        rs2Value := bp.data
      }
    }

    io.out.issue(u).valid := selectedValids(u)
    io.out.issue(u).bits  := entry.bits
    io.out.issue(u).bits.rs1data := rs1Value
    io.out.issue(u).bits.rs2data := rs2Value

    when(io.out.issue(u).fire) {
      entries(idx).valid := false.B
    }
  }

  // === 前馈命中 → 更新 ready + data ===
  for (i <- 0 until RS_ALU_SIZE) {
    val entry = entries(i)
    when(entry.valid){
      for (j <- 0 until NUM_BYPASS_PORTS) {
        val bp = io.in.bypass(j)
        when(bp.valid && entry.bits.useRs1 && !entry.bits.rs1Ready && bp.reg.phyDest === entry.bits.phyRs1) {
          entry.bits.rs1data := bp.data
          entry.bits.rs1Ready := true.B
        }
        when(bp.valid && entry.bits.useRs2 && !entry.bits.rs2Ready && bp.reg.phyDest === entry.bits.phyRs2) {
          entry.bits.rs2data := bp.data
          entry.bits.rs2Ready := true.B
        }
      }
    }
  }
  
  when(io.in.rollback.valid) {
    for (i <- 0 until RS_ALU_SIZE) {
      val entry = entries(i)
      when(entry.valid && isAfterRollback(entry.bits.robIdx, io.in.rollback.bits)) {
        entry.valid := false.B
      }
    }
  }
}

object RsUtils {
  def getNFreeSlots(free: Vec[Bool], count: Int): Vec[UInt] = {
    val idxVec = Wire(Vec(count, UInt(log2Ceil(RS_ALU_SIZE).W)))
    val used = Wire(Vec(RS_ALU_SIZE, Bool()))
    used.foreach(_ := false.B)

    for (i <- 0 until count) {
      val maskVec = Wire(Vec(RS_ALU_SIZE, Bool()))
      for (j <- 0 until RS_ALU_SIZE) {
        maskVec(j) := free(j) && !used(j)
      }
      val found = maskVec.reduce(_ || _)
      val chosen = PriorityEncoderOH(maskVec)
      val chosenIdx = PriorityEncoder(maskVec)
      idxVec(i) := Mux(found, chosenIdx, 0.U)

      for (j <- 0 until RS_ALU_SIZE) {
        when(chosen(j)) {
          used(j) := true.B
        }
      }
    }

    idxVec
  }

  def isAfterRollback(robIdx: UInt, rollback: RsRollbackEntry): Bool = {
    val tail = rollback.tailIdx
    val target = rollback.rollbackIdx
    Mux(tail >= target,
      robIdx > target && robIdx < tail,
      robIdx > target || robIdx < tail
    )
  }
}
