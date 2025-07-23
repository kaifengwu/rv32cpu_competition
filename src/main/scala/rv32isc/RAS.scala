package rv32isc
import chisel3._
import chisel3.util._

import bundles._
import config.Configs._
import config.OoOParams._
import config.InstType.BR

class RAS extends Module {
  val io = IO(new RASBundle)

  // === 栈结构 ===
  val stack = Reg(Vec(RAS_DEPTH, UInt(ADDR_WIDTH.W)))
  val depth = RegInit(0.U(log2Ceil(RAS_DEPTH).W))

  // === 溢出计数器 ===
  val overflowCounter = RegInit(0.U(log2Ceil(RAS_DEPTH * 16).W))

  // === 快照栈结构 ===
  class Snapshot extends Bundle {
    val pc = UInt(ADDR_WIDTH.W)
    val depth = UInt(log2Ceil(RAS_DEPTH).W)
    val stackSnapshot = Vec(RAS_DEPTH, UInt(ADDR_WIDTH.W))
    val overflowCounter = UInt(log2Ceil(RAS_DEPTH * 16).W)
  }

  val checkpointStack = RegInit(
    VecInit(Seq.fill(MAX_RAS_CHECKPOINTS)(0.U.asTypeOf(new Snapshot)))
  )
  val checkpointValid = RegInit(VecInit(Seq.fill(MAX_RAS_CHECKPOINTS)(false.B)))

  // === 栈满/空判断 ===
  val isFull = depth === (RAS_DEPTH - 1).U
  val isEmpty = depth === 0.U

  // === 压栈逻辑 ===
  for (i <- 0 until FETCH_WIDTH) {
    when(io.in.pushReqVec(i).valid && !io.in.stall && !io.in.rollback.valid) {
      when(!isFull) {
        stack(depth) := io.in.pushReqVec(i).bits
        depth := depth + 1.U
      }.otherwise {
        overflowCounter := overflowCounter + 1.U
      }
    }
  }

  // === 出栈逻辑 ===
  val RetStall = RegInit(false.B)
  when(io.in.popValid && !io.in.stall && !io.in.rollback.valid) {
    when(!isEmpty) {
      depth := depth - 1.U
    }.elsewhen(isFull) {
      overflowCounter := overflowCounter - 1.U
      RetStall := true.B
    }
  }
  when(io.in.retcommit.valid && io.in.retcommit.bits === true.B) {
    RetStall := false.B
  }

  // === 预测输出 ===

  val topIdx = depth - 1.U
  val topVal = Mux(isEmpty, 0.U, stack(topIdx))




  val snapshotRing = Reg(Vec(MAX_RAS_CHECKPOINTS, new Snapshot))
  val validBits = RegInit(VecInit(Seq.fill(MAX_RAS_CHECKPOINTS)(false.B)))
  val headPtr = RegInit(0.U(log2Ceil(MAX_RAS_CHECKPOINTS).W))
  val tailPtr = RegInit(0.U(log2Ceil(MAX_RAS_CHECKPOINTS).W))

  // 快照保存
  for (i <- 0 until ISSUE_WIDTH) {
    val count = PopCount(io.in.checkpoint.map(_.valid).slice(0,i+1))
    val idx = (tailPtr + count - 1.U)(log2Ceil(MAX_RAS_CHECKPOINTS) - 1,0)
    when(io.in.checkpoint(i).valid && !io.in.rollback.valid && !io.in.stall) {
      snapshotRing(idx).pc := io.in.checkpoint(i).bits
      snapshotRing(idx).depth := depth
      snapshotRing(idx).overflowCounter := overflowCounter
      for (j <- 0 until RAS_DEPTH) {
        snapshotRing(idx).stackSnapshot(j) := stack(j)
      }
      validBits(idx) := true.B
    }
  }
  val snapshotCount = Wire(UInt(log2Ceil(ISSUE_WIDTH + 1).W))
  val hasSnapshot = WireDefault(false.B)
  snapshotCount := PopCount(io.in.checkpoint.map(_.valid))
  when(!io.in.rollback.valid && !io.in.stall) { 
    tailPtr := (tailPtr + snapshotCount)(log2Ceil(MAX_RAS_CHECKPOINTS) - 1, 0 )
  }

  // 快照commit
  when(io.in.commit.valid && !io.in.rollback.valid && !io.in.stall) {
    when(validBits(headPtr) && snapshotRing(headPtr).pc === io.in.commit.bits) {
      validBits(headPtr) := false.B
      headPtr := (headPtr + 1.U)(log2Ceil(MAX_RAS_CHECKPOINTS) - 1,0)
    }
  }

  // 快照rollback
  val rollbackIdx = Wire(UInt(log2Ceil(MAX_RAS_CHECKPOINTS).W))

  when(io.in.rollback.valid) {
    // 扫描 snapshotRing，找出目标PC的位置
    for (k <- 0 until RAS_DEPTH) {
      stack(k) := snapshotRing(headPtr).stackSnapshot(k)
    }
    depth := snapshotRing(headPtr).depth
    overflowCounter := snapshotRing(headPtr).overflowCounter
    tailPtr := 0.U
    headPtr := 0.U
    snapshotCount := 0.U
    for(i <- 0 until MAX_RAS_CHECKPOINTS){ 
      snapshotRing(i) := 0.U.asTypeOf(new Snapshot)
    }
    RetStall := false.B
  }

  io.out.predictedRet.valid := !isEmpty
  io.out.predictedRet.bits := topVal
  io.out.debugTop := topVal
  io.out.currentDepth := depth
  io.out.retstall := RetStall
}

