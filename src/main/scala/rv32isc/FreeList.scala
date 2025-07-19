package rv32isc

import chisel3._
import chisel3.util._

import config.OoOParams._
import bundles._

class FreeList extends Module {
  val io = IO(new FreeListIO)

  // === 空闲物理寄存器池：初始化为 p32 ~ p127 ===
  val freeList = RegInit(VecInit((ARCH_REG_NUM until PHYS_REG_NUM).map(_.U(PHYS_REG_IDX_WIDTH.W))))
  val headPtr  = RegInit(0.U(log2Ceil(FREELIST_SIZE).W))              // 分配指针
  val tailPtr  = RegInit(FREELIST_SIZE.U(log2Ceil(FREELIST_SIZE).W)) // 回收指针
  val count    = RegInit(FREELIST_SIZE.U(log2Ceil(FREELIST_SIZE + 1).W))

  // === 分配逻辑 ===
  val allocCount = Wire(Vec(ISSUE_WIDTH, UInt(log2Ceil(FREELIST_SIZE + 1).W)))

  for (i <- 0 until ISSUE_WIDTH) {
    if (i == 0) {
      allocCount(i) := 0.U
    } else {
      allocCount(i) := PopCount(io.in.allocate.take(i))
    }

    val index = headPtr + allocCount(i)
    val wrappedIdx = Mux(index >= FREELIST_SIZE.U, index - FREELIST_SIZE.U, index)
    val allocReg = freeList(wrappedIdx)

    io.out.phyRd(i) := Mux(io.in.allocate(i), allocReg, 0.U)
  }

  val totalAlloc = PopCount(io.in.allocate)
  val rawNewHead = headPtr + totalAlloc
  headPtr := Mux(rawNewHead >= FREELIST_SIZE.U, rawNewHead - FREELIST_SIZE.U, rawNewHead)
  count   := count - totalAlloc

  // === 回收逻辑 ===
  for (i <- 0 until COMMIT_WIDTH) {
    when(io.in.dealloc(i).valid) {
      val idx = tailPtr + i.U
      val wrappedIdx = Mux(idx >= FREELIST_SIZE.U, idx - FREELIST_SIZE.U, idx)
      freeList(wrappedIdx) := io.in.dealloc(i).bits
    }
  }

  val totalDealloc = PopCount(io.in.dealloc.map(_.valid))
  val rawNewTail = tailPtr + totalDealloc
  tailPtr := Mux(rawNewTail >= FREELIST_SIZE.U, rawNewTail - FREELIST_SIZE.U, rawNewTail)
  count   := count + totalDealloc
}

