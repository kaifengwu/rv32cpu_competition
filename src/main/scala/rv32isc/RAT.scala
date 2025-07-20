package rv32isc

import chisel3._
import chisel3.util._

import config.Configs._
import config.OoOParams._
import bundles._

class RAT extends Module {

  val io = IO(new RATIO)
  val SNAP_DEPTH = 16

  val table = RegInit(
    VecInit((0 until ARCH_REG_NUM).map(_.U(PHYS_REG_IDX_WIDTH.W)))
  )

  // === 快照 ===
  val snapshotTables = Reg(
    Vec(SNAP_DEPTH, Vec(ARCH_REG_NUM, UInt(PHYS_REG_IDX_WIDTH.W)))
  )
  val snapshotTags = Reg(Vec(SNAP_DEPTH, UInt(ADDR_WIDTH.W)))
  val headPtr = RegInit(0.U(log2Ceil(SNAP_DEPTH).W))

  // === 查询映射 ===
  for (i <- 0 until ISSUE_WIDTH) {
    io.out.phyRs1(i) := table(io.in.logicRs1(i))
    io.out.phyRs2(i) := table(io.in.logicRs2(i))
    io.out.oldPhyRd(i) := table(io.in.logicRd(i))
  }

  // === 写入新映射 ===
  for (i <- 0 until ISSUE_WIDTH) {
    when(io.in.wen(i) && io.in.logicRd(i) =/= 0.U) {
      table(io.in.logicRd(i)) := io.in.phyRd(i)
    }
  }

  // === 创建快照 ===
  for (i <- 0 until ISSUE_WIDTH) {
    when(io.in.snapshot(i).valid) {
      snapshotTables(headPtr) := table
      snapshotTags(headPtr) := io.in.snapshot(i).bits
      headPtr := (headPtr + 1.U) % SNAP_DEPTH.U
    }
  }

  // === 回滚 ===
  when(io.in.rollback.valid) {
    val rollbackTag = io.in.rollback.bits
    val rollbackHit = WireDefault(false.B)
    val rollbackIdx = WireDefault(0.U(log2Ceil(SNAP_DEPTH).W))

    for (i <- 0 until SNAP_DEPTH) {
      when(snapshotTags(i) === rollbackTag && !rollbackHit) {
        rollbackIdx := i.U
        rollbackHit := true.B
      }
    }

    when(rollbackHit) {
      table := snapshotTables(rollbackIdx)
      headPtr := Mux(rollbackIdx === 0.U, (SNAP_DEPTH - 1).U, rollbackIdx - 1.U)
    }
  }

  val tailPtr = RegInit(0.U(log2Ceil(SNAP_DEPTH).W))

// === 分支成功后清除快照 ===
  when(io.in.commit.valid) {
    when(snapshotTags(tailPtr) === io.in.commit.bits) {
      snapshotTags(tailPtr) := 0.U
      tailPtr := Mux(tailPtr === (SNAP_DEPTH - 1).U, 0.U, tailPtr + 1.U)
    }
  }
}
