package rv32isc
import chisel3._
import chisel3.util._

import bundles._
import config.Configs._
import config.OoOParams._

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
    when(io.in.pushValidVec(i)) {
      when(!isFull) {
        stack(depth) := io.in.pushDataVec(i)
        depth := depth + 1.U
      }.otherwise {
        overflowCounter := overflowCounter + 1.U
      }
    }
  }

  // === 出栈逻辑 ===
  val RetStall = RegInit(false.B)
  when(io.in.popValid) {
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
    when(io.in.checkpoint(i).valid) {
      snapshotRing(tailPtr).pc := io.in.checkpoint(i).bits
      snapshotRing(tailPtr).depth := depth
      for (j <- 0 until RAS_DEPTH) {
        snapshotRing(tailPtr).stackSnapshot(j) := stack(j)
      }
      validBits(tailPtr) := true.B
      tailPtr := tailPtr + 1.U
    }
  }

  // 快照commit

  for (i <- 0 until ISSUE_WIDTH) {
    when(io.in.commit(i).valid) {
      when(
        validBits(headPtr) && snapshotRing(headPtr).pc === io.in.commit(i).bits
      ) {
        validBits(headPtr) := false.B
        headPtr := headPtr + 1.U
      }
    }
  }

  // 快照rollback

  for (i <- 0 until ISSUE_WIDTH) {
    when(io.in.rollback(i).valid) {
      // 扫描 snapshotRing，找出目标PC的位置
      for (j <- 0 until MAX_RAS_CHECKPOINTS) {
        val idx = (headPtr + j.U)(log2Ceil(MAX_RAS_CHECKPOINTS) - 1, 0)
        when(
          validBits(idx) && snapshotRing(idx).pc === io.in.rollback(i).bits
        ) {
          // 恢复
          depth := snapshotRing(idx).depth
          for (k <- 0 until RAS_DEPTH) {
            stack(k) := snapshotRing(idx).stackSnapshot(k)
          }

          // 清除 [headPtr, idx] 所有项
          for (m <- 0 to j) {
            val clearIdx = (headPtr + m.U)(log2Ceil(MAX_RAS_CHECKPOINTS) - 1, 0)
            validBits(clearIdx) := false.B
          }

          // 回退 tailPtr
          tailPtr := (headPtr + j.U - 1.U)(log2Ceil(MAX_RAS_CHECKPOINTS) - 1, 0)
          RetStall := false.B
        }
      }
    }
  }

  io.out.predictedRet.valid := !isEmpty
  io.out.predictedRet.bits := topVal
  io.out.debugTop := topVal
  io.out.currentDepth := depth
  io.out.retstall := RetStall
}

