package rv32isc

import chisel3._
import chisel3.util._
import bundles._
import config.Configs._
import config.OoOParams._
import RsUtils._

class BrRS extends Module {
  val io = IO(new BrRSIO)

  // === BR 保留站结构 ===
  val entries = RegInit(VecInit(Seq.fill(RS_BR_SIZE)(0.U.asTypeOf(Valid(new BrIssueEntry)))))
  val tailPtr = RegInit(0.U(log2Ceil(RS_BR_SIZE).W))
  val headPtr = RegInit(0.U(log2Ceil(RS_BR_SIZE).W))

  val validVec = entries.map(_.valid)
  val spaceLeft = PopCount(entries.map(!_.valid))
  io.out.freeEntryCount := Mux(spaceLeft > ISSUE_WIDTH.U, ISSUE_WIDTH.U, spaceLeft)

  // === 入队逻辑 ===
  val enqVec = io.in.enq
  val enqCount = PopCount(enqVec.map(_.valid))
  val realEnq = WireDefault(VecInit(Seq.fill(ISSUE_WIDTH)(false.B)))

  val inputStall = enqCount >= spaceLeft

  io.out.isFull := inputStall || io.in.rollback.valid
  for (i <- 0 until ISSUE_WIDTH) {
    when(!inputStall){
      realEnq(i) := enqVec(i).valid && (i.U < spaceLeft)
    }
  }

  for (i <- 0 until ISSUE_WIDTH) {
    when(realEnq(i) && !io.in.rollback.valid) {
      val entry = enqVec(i).bits
      val enqIdx = PopCount(realEnq.slice(0, i))
      val idx = (tailPtr + enqIdx)(log2Ceil(RS_BR_SIZE) - 1, 0)
      entries(idx).valid := true.B
      entries(idx).bits  := entry
    }
  }


  // === 发射逻辑（FIFO 顺序） ===
  val issuePorts = io.out.issue
  val canFire = WireDefault(VecInit(Seq.fill(BR_UNITS)(false.B)))

  for (u <- 0 until BR_UNITS) {
    val idx = (headPtr + u.U)(log2Ceil(RS_BR_SIZE) - 1,0)
    val entry = entries(idx)

    val bypassRs1 = WireDefault(false.B)
    val bypassRs2 = WireDefault(false.B)


    // 构造发射 entry，补上 bypass 数据
    val rs1Val = WireDefault(entry.bits.rs1data)
    val rs2Val = WireDefault(entry.bits.rs2data)

    for (j <- 0 until NUM_BYPASS_PORTS) {
      val bp = io.in.bypass(j)
      when(bp.valid && entry.bits.useRs1 && !entry.bits.rs1Ready && bp.reg.phyDest === entry.bits.phyRs1) {
        rs1Val := bp.data
        bypassRs1 := true.B
      }
      when(bp.valid && entry.bits.useRs2 && !entry.bits.rs2Ready && bp.reg.phyDest === entry.bits.phyRs2) {
        rs2Val := bp.data
        bypassRs2 := true.B
      }
    }
    val rs1Ok = !entry.bits.useRs1 || entry.bits.rs1Ready || bypassRs1
    val rs2Ok = !entry.bits.useRs2 || entry.bits.rs2Ready || bypassRs2
    canFire(u) := entry.valid && rs1Ok && rs2Ok && !io.in.rollback.valid

    val outEntry = WireDefault(entry.bits)
    outEntry.rs1data := rs1Val
    outEntry.rs2data := rs2Val

    issuePorts(u).valid := canFire(u)
    issuePorts(u).bits := Mux(canFire(u), outEntry, 0.U.asTypeOf(new BrIssueEntry))
  }



  for(i <- 0 until BR_UNITS){ 
    val idx = (headPtr + i.U)(log2Ceil(RS_BR_SIZE) - 1,0)
    when(canFire(i) && !io.in.rollback.valid){ 
      entries(idx).valid := false.B
    }
  }
  //headPtr前进

  val issueFire = WireInit(VecInit((0 until BR_UNITS).map(i => issuePorts(i).fire)))
  val issueCount = PopCount(issueFire)

  when(!io.in.rollback.valid) {
    headPtr := (headPtr + issueCount)(log2Ceil(RS_BR_SIZE) - 1, 0)
  }

  // === 前馈命中 → 更新 ready + data ===
  for (i <- 0 until RS_BR_SIZE) {
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

  // === 回滚处理 ===
  when(io.in.rollback.valid) {
    val rollbackIdx = io.in.rollback.bits.rollbackIdx
    val tailIdx = io.in.rollback.bits.tailIdx
    val clearVec = WireDefault(VecInit(Seq.fill(RS_BR_SIZE)(false.B)))

    for (i <- 0 until RS_BR_SIZE) {
      val entry = entries(i)
      when(entry.valid && isAfterRollback(entry.bits.robIdx, io.in.rollback.bits)) {
        clearVec(i) := true.B
        entry.valid := false.B
      }
    }

    val hasRollback = clearVec.asUInt.orR
    val rollbackCount = PopCount(clearVec)
    when(hasRollback) {
      tailPtr := (tailPtr - rollbackCount)(log2Ceil(RS_BR_SIZE) - 1, 0)
    }
  }.elsewhen(realEnq.asUInt.orR) {
    tailPtr := ( tailPtr + PopCount(realEnq.asUInt))(log2Ceil(RS_BR_SIZE) - 1 , 0 )
  }
}

