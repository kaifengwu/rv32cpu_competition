package rv32isc

import chisel3._
import chisel3.util._
import config.OoOParams._
import config.Configs._
import bundles._
import RsUtils._

class StoreQueue extends Module {
  val io = IO(new StoreQueueIO)

  val queue = Reg(Vec(ROB_SIZE, Valid(new StoreEntry)))
  val headPtr = RegInit(0.U(log2Ceil(ROB_SIZE).W))
  val tailPtr = RegInit(0.U(log2Ceil(ROB_SIZE).W))

  // === 入队逻辑 ===
  val nextTail = (tailPtr + 1.U)(log2Ceil(ROB_SIZE) - 1 ,0)
  val isFull = nextTail === headPtr

  when(io.in.enq.valid && !isFull && !io.in.rollback.valid) {
    queue(tailPtr).valid := true.B
    queue(tailPtr).bits := io.in.enq.bits
    tailPtr := nextTail
  }

  // === 提交逻辑 ===
  val commitValid = queue(headPtr).valid && io.in.commit
  io.out.commitValid := commitValid
  io.out.commitEntry := queue(headPtr).bits

  when(commitValid && !io.in.rollback.valid) {
    queue(headPtr).valid := false.B
    headPtr := (headPtr + 1.U)(log2Ceil(ROB_SIZE) - 1 ,0)
  }

// === Load 旁路逻辑（从最近到最早）===
  val matches = Wire(Vec(ROB_SIZE, Bool()))
  val dataVec = Wire(Vec(ROB_SIZE, UInt(DATA_WIDTH.W)))

  for (i <- 0 until ROB_SIZE) {
    val revIdx = (tailPtr - 1.U - i.U)(log2Ceil(ROB_SIZE) - 1, 0)
    val valid  = queue(revIdx).valid
    val addrMatch = io.in.bypassAddr.valid && (queue(revIdx).bits.addr === io.in.bypassAddr.bits)
  
    matches(i) := valid && addrMatch
    dataVec(i) := queue(revIdx).bits.data
  }

  val hit = matches.asUInt.orR
  val selIdx = PriorityEncoder(matches)
  io.out.bypass.hit  := hit
  io.out.bypass.data := Mux(hit, dataVec(selIdx), 0.U)

  // === 回滚逻辑 ===

  when(io.in.rollback.valid) {
    val valids = Wire(Vec(ROB_SIZE, Bool()))
    valids := VecInit(Seq.tabulate(ROB_SIZE) { i =>
      val idx = (headPtr + i.U)(log2Ceil(ROB_SIZE) - 1, 0)
      val keep = queue(idx).valid && !isAfterRollback(queue(idx).bits.robIdx, io.in.rollback.bits)

      queue(idx).valid := keep
      when(!keep) {
      queue(idx).bits := 0.U.asTypeOf(new StoreEntry)
    }
      keep
    })
 
    val count = PopCount(valids)
    tailPtr := (headPtr + count)(log2Ceil(ROB_SIZE) - 1, 0)
  }
}
