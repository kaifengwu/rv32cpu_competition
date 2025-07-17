package rv32isc
import chisel3._
import chisel3.util._

import bundles._
import config.OoOParams._
import config.Configs._

class BrReservationStation extends Module {
  val io = IO(new BrRSIO)

  val entries = Reg(Vec(RS_BR_SIZE, new BrIssueEntry))
  val validVec = RegInit(VecInit(Seq.fill(RS_BR_SIZE)(false.B)))
  val headPtr = RegInit(0.U(log2Ceil(RS_BR_SIZE).W))
  val tailPtr = RegInit(0.U(log2Ceil(RS_BR_SIZE).W))

// === 入队请求分析 ===
  val currentOccupied = PopCount(validVec)
  val enqValidVec = VecInit(io.in.enq.map(_.valid))
  val enqCount = PopCount(enqValidVec)
  val spaceLeft = PopCount(~validVec.asUInt)

  val notEnoughSpace = spaceLeft < enqCount
  val doEnqueue = !notEnoughSpace
  io.out.full := notEnoughSpace

// === 预计算 idx 和有效标记 ===
  val idxVec = Wire(Vec(ISSUE_WIDTH, UInt(log2Ceil(RS_BR_SIZE).W)))
  val doWriteVec = Wire(Vec(ISSUE_WIDTH, Bool()))

  for (i <- 0 until ISSUE_WIDTH) {
    idxVec(i) := (tailPtr + i.U)(log2Ceil(RS_BR_SIZE) - 1, 0)
    doWriteVec(i) := doEnqueue && io.in.enq(i).valid
    io.in.enq(i).ready := doEnqueue
  }

// === 真正入栈 ===
  for (i <- 0 until ISSUE_WIDTH) {
    when(doWriteVec(i)) {
      entries(idxVec(i)) := io.in.enq(i).bits
      validVec(idxVec(i)) := true.B
    }
  }

// === 更新 tailPtr ===
  when(doEnqueue && enqCount =/= 0.U) {
    tailPtr := (tailPtr + enqCount)(log2Ceil(RS_BR_SIZE) - 1, 0)
  }

  val FIRE_WIDTH = BR_UNITS
  val entryVec = Wire(Vec(FIRE_WIDTH, new BrIssueEntry))
  val entryIdxs = Wire(Vec(FIRE_WIDTH, UInt(log2Ceil(RS_BR_SIZE).W)))
  val fireVec = Wire(Vec(FIRE_WIDTH, Bool()))
  val firePrefix = Wire(Vec(FIRE_WIDTH, Bool()))

// === 拉出前 BR_UNITS 条候选指令 ===
  for (i <- 0 until FIRE_WIDTH) {
    val idx = (headPtr + i.U)(log2Ceil(RS_BR_SIZE) - 1, 0)
    entryIdxs(i) := idx
    val entry = entries(idx)
    val valid = validVec(idx)
    val ready = entry.ready1 && entry.ready2
    val execReady = io.out.issue(i).ready

    entryVec(i) := entry
    fireVec(i) := valid && ready && execReady
  }

// === 顺序限制：遇到第一个不能发射的就终止 ===
  val stopAt = Wire(UInt(log2Ceil(FIRE_WIDTH + 1).W))
  stopAt := 0.U
  for (i <- 0 until BR_UNITS) {
    when(!fireVec(i) && stopAt === 0.U) {
      stopAt := i.U
    }
  }
// === 发射数量（顺序限制） ===
  val fireCount = Mux(fireVec.reduce(_ && _), FIRE_WIDTH.U, stopAt)

// === 实际发射 ===
  for (i <- 0 until FIRE_WIDTH) {
    val fireThis = i.U < fireCount
    val idx = entryIdxs(i)

    io.out.issue(i).valid := fireThis
    io.out.issue(i).bits := entryVec(i)

    when(fireThis) {
      validVec(idx) := false.B
    }
  }

// === 更新 headPtr ===
  when(fireCount =/= 0.U) {
    headPtr := (headPtr + fireCount)(log2Ceil(RS_BR_SIZE) - 1, 0)
  }
}
