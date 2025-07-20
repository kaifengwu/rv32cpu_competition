package rv32isc

import chisel3._
import chisel3.util._
import config.Configs._
import config.OoOParams._
import bundles._

class RobIndexAllocator extends Module {
  val io = IO(new RobIndexAllocatorIO)

  // 环形缓冲指针：使用 ROB_IDX_WIDTH + 1 位（含 wrap 位）
  val head = RegInit(0.U((ROB_IDX_WIDTH + 1).W))
  val tail = RegInit(0.U((ROB_IDX_WIDTH + 1).W))

  // 当前分配和提交数量
  val allocCount  = PopCount(io.in.allocateValid)
  val commitCount = PopCount(io.in.commitValid)

  // ROB 剩余空间计算（考虑 wrap）
  val freeCount = Wire(UInt((ROB_IDX_WIDTH + 1).W))
  when(head >= tail) {
    freeCount := ROB_SIZE.U - (head - tail)
  } .otherwise {
    freeCount := tail - head
  }

  // 是否已满
  io.out.isFull := freeCount < allocCount

  // 分配编号输出（截断掉 wrap 位）
  for (i <- 0 until ISSUE_WIDTH) {
    io.out.allocateIdx(i) := (head + i.U)(ROB_IDX_WIDTH - 1, 0)
  }

  // 指针更新
  when(!io.out.isFull) {
    head := head + allocCount
  }
  tail := tail + commitCount
}
