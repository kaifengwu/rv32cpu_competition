package rv32isc

import chisel3._
import chisel3.util._

import config.Configs._
import config.OoOParams._
import bundles._

class RAT extends Module {

  val io = IO(new RATIO)
  val SNAP_DEPTH = 16

  val table = RegInit(VecInit((0 until ARCH_REG_NUM).map(_.U(PHYS_REG_IDX_WIDTH.W))))

  // === 快照 ===
  val snapshotTables = Reg(Vec(SNAP_DEPTH, Vec(ARCH_REG_NUM, UInt(PHYS_REG_IDX_WIDTH.W))))
  val snapshotTags = Reg(Vec(SNAP_DEPTH, UInt(ADDR_WIDTH.W)))
  val snapshotValid = RegInit(VecInit(Seq.fill(SNAP_DEPTH)(false.B)))
  val tailPtr = RegInit(0.U(log2Ceil(SNAP_DEPTH).W))
  val headPtr = RegInit(0.U(log2Ceil(SNAP_DEPTH).W))

  for (i <- 0 until ISSUE_WIDTH) {
    // Rs1 forward
    val rs1Match = Wire(Bool())
    val rs1Data  = Wire(UInt(PHYS_REG_IDX_WIDTH.W))
    rs1Match := false.B
  
    for (j <- 0 until i) {
      when(io.in.wen(j) && io.in.logicRd(j) =/= 0.U && io.in.logicRs1(i) === io.in.logicRd(j)) {
        rs1Match := true.B
        rs1Data  := io.in.phyRd(j)
      }
    }
    io.out.phyRs1(i) := Mux(rs1Match,rs1Data,table(io.in.logicRs1(i)))

    // Rs2 forward
    val rs2Match = Wire(Bool())
    val rs2Data  = Wire(UInt(PHYS_REG_IDX_WIDTH.W))
    rs2Match := false.B
  
    for (j <- 0 until i) {
      when(io.in.wen(j) && io.in.logicRd(j) =/= 0.U && io.in.logicRs2(i) === io.in.logicRd(j)) {
        rs2Match := true.B
        rs2Data  := io.in.phyRd(j)
      }
    }
    io.out.phyRs2(i) := Mux(rs2Match,rs2Data,table(io.in.logicRs2(i)))
    // oldPhyRd 不需要 forward，因为是读旧的值
    io.out.oldPhyRd(i) := table(io.in.logicRd(i))
}

  // === 写入新映射 ===
  for (i <- 0 until ISSUE_WIDTH) {
    when(io.in.wen(i) && io.in.logicRd(i) =/= 0.U && !io.in.rollback.valid && !io.in.stall) {
      table(io.in.logicRd(i)) := io.in.phyRd(i)
    }
  }

  // === 创建快照 ===
  for (i <- 0 until ISSUE_WIDTH) {
    val count = PopCount(io.in.snapshot.map(_.valid).slice(0,i+1))
    val idx = (tailPtr + count - 1.U)(log2Ceil(SNAP_DEPTH) - 1 , 0)
    when(io.in.snapshot(i).valid && !io.in.rollback.valid && !io.in.stall) {
      snapshotTables(idx) := table
      snapshotTags(idx) := io.in.snapshot(i).bits
      snapshotValid(idx) := true.B
    }
  }
  val snapshotCount = PopCount(io.in.snapshot.map(_.valid))

  // === 回滚 ===
  when(io.in.rollback.valid) {
    table := snapshotTables(headPtr)
    for (i <- 0 until SNAP_DEPTH) {
      snapshotTags(i) := 0.U
      snapshotTables(i) := VecInit(Seq.fill(ARCH_REG_NUM)(0.U))
      snapshotValid(i) := false.B
    }
    headPtr := 0.U
    tailPtr := 0.U
  }.elsewhen(!io.in.stall) {
    tailPtr := (tailPtr + snapshotCount)(log2Ceil(SNAP_DEPTH) - 1,0)
  }


// === 分支成功后清除快照 ===
  when(io.in.commit.valid && !io.in.rollback.valid) {
    when(snapshotTags(headPtr) === io.in.commit.bits) {
      snapshotTags(headPtr) := 0.U
      snapshotValid(headPtr) := false.B
      snapshotTables(headPtr) := VecInit(Seq.fill(ARCH_REG_NUM)(0.U))
      headPtr := Mux(headPtr === (SNAP_DEPTH - 1).U, 0.U, headPtr + 1.U)
    }
  }
}
