package rv32isc

import chisel3._
import chisel3.util._
import config.OoOParams._
import config.Configs._
import bundles._

class StoreQueue extends Module {
  val RS_LS_SIZE = ROB_SIZE
  val io = IO(new StoreQueueIO)

  // 每个 entry 加一个编号 tag
  val queue    = Reg(Vec(RS_LS_SIZE, Valid(new StoreEntry)))
  val tags     = Reg(Vec(RS_LS_SIZE, UInt(log2Ceil(RS_LS_SIZE).W))) // 每条store的位置编号
  val count    = RegInit(0.U(log2Ceil(RS_LS_SIZE + 1).W))            // 当前有效数量
  val nextTag  = RegInit(0.U(log2Ceil(RS_LS_SIZE).W))                // 下一条store的编号

  // === 入队逻辑 ===
  io.in.enq.ready := count =/= RS_LS_SIZE.U
  when(io.in.enq.fire) {
    queue(count).valid := true.B
    queue(count).bits := io.in.enq.bits
    tags(count) := nextTag
    count := count + 1.U
    nextTag := nextTag + 1.U
  }

  // === 提交逻辑：总是提交第0位 ===
  io.out.commitValid := queue(0).valid
  io.out.commitEntry := queue(0).bits

  when(io.out.commitValid) {
    for (i <- 0 until RS_LS_SIZE - 1) {
      queue(i) := queue(i + 1)
      tags(i)  := tags(i + 1)
    }
    queue(RS_LS_SIZE - 1).valid := false.B
    count := count - 1.U
  }

  // === Load 旁路逻辑 ===
  val matches = Wire(Vec(RS_LS_SIZE, Bool()))
  val dataVec = Wire(Vec(RS_LS_SIZE, UInt(DATA_WIDTH.W)))

  for (i <- 0 until RS_LS_SIZE) {
    matches(i) := io.in.bypassAddr.valid && queue(i).valid && (queue(i).bits.addr === io.in.bypassAddr.bits)
    dataVec(i) := queue(i).bits.data
  }

  val revMatches = Reverse(matches.asUInt)
  val selIdx     = PriorityEncoder(revMatches)
  val hasMatch   = revMatches.orR
  val revDataVec = VecInit(dataVec.reverse)

  io.out.bypass.hit  := hasMatch
  io.out.bypass.data := Mux(hasMatch, revDataVec(selIdx), 0.U)

  // === 回滚逻辑 ===
  def isAfter(a: UInt, b: UInt): Bool = {
    val diff = a - b
    diff < (ROB_SIZE / 2).U
  }


  when(io.in.rollback) {
    val isKeep = Wire(Vec(RS_LS_SIZE, Bool()))
    val tagVec = Wire(Vec(RS_LS_SIZE, UInt(log2Ceil(RS_LS_SIZE).W)))

    for (i <- 0 until RS_LS_SIZE) {
      isKeep(i) := queue(i).valid && !isAfter(queue(i).bits.robIdx, io.in.rollbackTarget)
      tagVec(i) := tags(i)
    }

    val reversedIsKeep = Reverse(isKeep.asUInt)
    val maxIdx = PriorityEncoder(reversedIsKeep)
    val found  = reversedIsKeep.orR

    val revTagVec = VecInit(tagVec.reverse)
    val maxTag = Mux(found, revTagVec(maxIdx) + 1.U, 0.U)
    count := maxTag
  }
}
