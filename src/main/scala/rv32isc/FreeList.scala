package rv32isc

import chisel3._
import chisel3.util._

import config.OoOParams._
import bundles._

class FreeList extends Module {
  val io = IO(new FreeListIO)

  // === 空闲物理寄存器池：初始化为 p32 ~ p127 ===
  val freeList = RegInit(VecInit((ARCH_REG_NUM until PHYS_REG_NUM).map(_.U(PHYS_REG_IDX_WIDTH.W))))
  val tailPtr  = RegInit(0.U(log2Ceil(FREELIST_SIZE).W))              // 分配指针
  val headPtr  = RegInit(0.U(log2Ceil(FREELIST_SIZE).W)) // 回收指针
  val count    = RegInit(FREELIST_SIZE.U(log2Ceil(FREELIST_SIZE + 1).W))

  // === 分配逻辑 ===
  when(!io.in.rollbackTail.valid && !io.in.stall){
    for (i <- 0 until ISSUE_WIDTH) {
      val allocCount = PopCount(io.in.allocate.slice(0,i+1))
      val index = tailPtr + allocCount(i)
      val wrappedIdx = Mux(index >= FREELIST_SIZE.U, index - FREELIST_SIZE.U, index)
      val allocReg = freeList(wrappedIdx)
      io.out.phyRd(i) := Mux(io.in.allocate(i), allocReg, 0.U)
    }
  }

  val totalAlloc = PopCount(io.in.allocate)
  val rawNewtail = tailPtr + totalAlloc


  // === 回收逻辑 ===
  for (i <- 0 until MAX_COMMIT_WB) {
    when(io.in.dealloc(i).valid && !io.in.stall && !io.in.rollbackTail.valid) {
      val idx = headPtr + i.U
      val wrappedIdx = Mux(idx >= FREELIST_SIZE.U, idx - FREELIST_SIZE.U, idx)
      freeList(wrappedIdx) := io.in.dealloc(i).bits
    }
  }

  val totalDealloc = PopCount(io.in.dealloc.map(_.valid))
  val rawNewhead = headPtr + totalDealloc
  when(io.in.rollbackTail.valid){
    tailPtr := io.in.rollbackTail.bits
    io.out.tailPtr := io.in.rollbackTail.bits
    headPtr := headPtr
  }.elsewhen(io.in.stall){
    tailPtr := Mux(rawNewtail >= FREELIST_SIZE.U, rawNewtail - FREELIST_SIZE.U, rawNewtail)
    count   := count - totalAlloc + totalDealloc
    io.out.tailPtr := Mux(rawNewtail >= FREELIST_SIZE.U, rawNewtail - FREELIST_SIZE.U, rawNewtail)
    headPtr := Mux(rawNewhead >= FREELIST_SIZE.U, rawNewhead - FREELIST_SIZE.U, rawNewhead)
  }
}

