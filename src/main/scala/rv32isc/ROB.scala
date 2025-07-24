package rv32isc

import chisel3._
import chisel3.util._
import config.Configs._
import config.OoOParams._
import bundles._

class ROB extends Module {
  val io = IO(new ROBIO)
  // === ROB 表项结构 ===
  class RobEntry extends Bundle {
    val valid   = Bool()
    val hasRd   = Bool()
    val isStore = Bool()
    val rd      = UInt(PHYS_REG_IDX_WIDTH.W)
    val lrd     = UInt(REG_NUMS_LOG.W)
    val pc      = UInt(ADDR_WIDTH.W)
    val ready   = Bool() // 仅标志写回是否完成
  }

  val rob = RegInit(VecInit(Seq.fill(ROB_SIZE)(0.U.asTypeOf(new RobEntry))))
  val head = RegInit(0.U(ROB_IDX_WIDTH.W)) // 提交指针
  val tail = RegInit(0.U(ROB_IDX_WIDTH.W)) // 分配指针

  // === 分配逻辑 ===
  val allocCount = Mux(io.in.rollback.valid, 0.U, PopCount(io.in.allocate.map(_.valid)))

  val enq = io.in.allocate
  for (i <- 0 until ISSUE_WIDTH) {
    val tailIdx = (tail + i.U)(ROB_IDX_WIDTH - 1, 0)
    when(enq(i).valid && !io.in.rollback.valid) {
      rob(tailIdx).valid   := true.B
      rob(tailIdx).pc      := enq(i).bits.pc
      rob(tailIdx).hasRd   := enq(i).bits.hasRd
      rob(tailIdx).rd      := enq(i).bits.rd
      rob(tailIdx).lrd     := enq(i).bits.lrd
      rob(tailIdx).isStore := enq(i).bits.isStore
    }
  }
  val hasInput = enq.map(_.valid).reduce(_||_)
  val inputNum = PopCount(enq.map(_.valid))


  // === 写回逻辑 ===
  for (i <- 0 until EXEC_UNITS) {
    val wb = io.in.writeback(i)
    val idx = wb.bits.robIdx
    // 如果 rollback.valid，则写回仅允许不在回滚区间的 robIdx
    val notRolledBack = WireDefault(true.B)
    when(io.in.rollback.valid) {
      when(tail >= io.in.rollback.bits) {
        // 普通情况：[rollback, tail)
        notRolledBack := !(idx >= io.in.rollback.bits && idx < tail)
      }.otherwise {
        // 环形情况：[rollback, ROB_SIZE) ∪ [0, tail)
        notRolledBack := !(idx >= io.in.rollback.bits || idx < tail)
      }
    }

    when(wb.valid && notRolledBack) {
      rob(idx).ready := true.B
    }
  }

  // === 回滚逻辑 ===
  when(io.in.rollback.valid) {
  // 回滚范围：[io.in.rollback.bits, tail)
    val rollbackIdx = io.in.rollback.bits
    val rollbackRange = Wire(Vec(ROB_SIZE, Bool()))
    rollbackRange := VecInit(Seq.tabulate(ROB_SIZE) { i =>
  // 判断 i 是否 ∈ [rollbackIdx, tail)（环形）
      Mux(tail >= rollbackIdx,
        i.U >= rollbackIdx && i.U < tail,
        i.U >= rollbackIdx || i.U < tail
      )
    })

    for (i <- 0 until ROB_SIZE) {
      when(rollbackRange(i)) {
        rob(i).valid   := false.B
        rob(i).ready   := false.B
        rob(i).hasRd   := false.B
        rob(i).isStore := false.B
        rob(i).rd      := 0.U
        rob(i).lrd     := 0.U
      }
    }
  // 移动 tail 到 rollbackIdx
    tail := rollbackIdx
  }.otherwise{
    when(hasInput){ 
      tail := (tail +& allocCount)(ROB_IDX_WIDTH - 1,0)
    }
  }

  
  // 顺序判断 head~head+MAX_COMMIT_WIDTH-1 项

  // === 构造提交窗口 ===
  // === 提交限制 ===
  val readyVec    = Wire(Vec(MAX_COMMIT_WIDTH, Bool()))
  val isWbVec     = Wire(Vec(MAX_COMMIT_WIDTH, Bool()))
  val isStoreVec  = Wire(Vec(MAX_COMMIT_WIDTH, Bool()))
  val isBranchVec = Wire(Vec(MAX_COMMIT_WIDTH, Bool()))
  val robIdxVec   = Wire(Vec(MAX_COMMIT_WIDTH, UInt(ROB_IDX_WIDTH.W)))

  // 预处理前 MAX_COMMIT_WIDTH 个 entry
  for (i <- 0 until MAX_COMMIT_WIDTH) {
    val idx = (head + i.U)(ROB_IDX_WIDTH - 1, 0)
    val entry = rob(idx)
    readyVec(i)    := entry.valid && entry.ready
    isWbVec(i)     := entry.valid && entry.ready && entry.hasRd
    isStoreVec(i)  := entry.valid && entry.ready && entry.isStore
    isBranchVec(i) := entry.valid && entry.ready && !entry.hasRd && !entry.isStore
    robIdxVec(i)   := idx
  }

  // === 累加合法提交窗口 ===

  val allowVec = WireDefault(VecInit(Seq.fill(MAX_COMMIT_WIDTH)(false.B)))

  val wbLimitVec = Wire(Vec(MAX_COMMIT_WIDTH, UInt(log2Ceil(MAX_COMMIT_WB + 1).W)))
  val storeLimitVec = Wire(Vec(MAX_COMMIT_WIDTH, UInt(log2Ceil(MAX_COMMIT_STORE + 1).W)))
  val brLimitVec = Wire(Vec(MAX_COMMIT_WIDTH, UInt(log2Ceil(MAX_COMMIT_BR + 1).W)))

  for (i <- 0 until MAX_COMMIT_WIDTH) {
    wbLimitVec(i) := PopCount(isWbVec.slice(0, i + 1))
    storeLimitVec(i) := PopCount(isStoreVec.slice(0, i + 1))
    brLimitVec(i) := PopCount(isBranchVec.slice(0, i + 1))
  }

  allowVec(0) := readyVec(0) &&(wbLimitVec(0) <= MAX_COMMIT_WB.U) && (storeLimitVec(0) <= MAX_COMMIT_STORE.U) && (brLimitVec(0) <= MAX_COMMIT_BR.U)
  for (i <- 1 until MAX_COMMIT_WIDTH) {
    allowVec(i) := allowVec(i - 1) && readyVec(i) && (wbLimitVec(i) <= MAX_COMMIT_WB.U) && (storeLimitVec(i) <= MAX_COMMIT_STORE.U) && (brLimitVec(i) <= MAX_COMMIT_BR.U)
  }

  // === 找出可提交的数量 ===
  val allowMask = allowVec.map(_.asUInt).reverse.reduce(_ ## _)
  val commitMask = ~allowMask
  val commitValid = commitMask.orR   // 是否有不允许提交的项
  val commitWidth = Mux(commitValid, PriorityEncoder(commitMask), MAX_COMMIT_WIDTH.U)
  when(!io.in.rollback.valid) {
    head := (head +& commitWidth)(ROB_IDX_WIDTH - 1, 0)
  }

  val Wb_Wire = WireDefault(VecInit(Seq.fill(MAX_COMMIT_WB)(0.U.asTypeOf(Valid(new RobCommitWbEntry)))))
  val Store_Wire = WireDefault(VecInit(Seq.fill(MAX_COMMIT_STORE)(0.U.asTypeOf(Valid(new RobCommitStoreEntry)))))

  // === 提交通道连接 ===
  for (i <- 0 until MAX_COMMIT_WIDTH) {
    val doCommit = i.U < commitWidth
    val idx = robIdxVec(i)
    val entry = rob(idx)

  // === WB 提交 ===
    when(doCommit && isWbVec(i) && wbLimitVec(i) < MAX_COMMIT_WB.U) {
      Wb_Wire(wbLimitVec(i)).valid := true.B
      Wb_Wire(wbLimitVec(i)).bits.robIdx := idx
      Wb_Wire(wbLimitVec(i)).bits.rd     := entry.rd
      Wb_Wire(wbLimitVec(i)).bits.lrd    := entry.lrd
      Wb_Wire(wbLimitVec(i)).bits.hasRd  := true.B
      Wb_Wire(wbLimitVec(i)).bits.isStore := false.B
    }

  // === Store 提交 ===
    when(doCommit && isStoreVec(i) && storeLimitVec(i) < MAX_COMMIT_STORE.U) {
      Store_Wire(storeLimitVec(i)).valid := true.B
      Store_Wire(storeLimitVec(i)).bits.robIdx := idx
      Store_Wire(storeLimitVec(i)).bits.isStore := true.B

    }
  // === 回收 ROB entry（逻辑清空）
    when(doCommit) {
      entry := 0.U.asTypeOf(new RobEntry)
    }
  }

  when(!io.in.rollback.valid){
    for(i <- 0 until MAX_COMMIT_WB){ 
      io.out.commit_wb(i) := Wb_Wire(i)
    }
    for(i <- 0 until MAX_COMMIT_STORE){ 
      io.out.commit_store(i) := Store_Wire(i)
    }
    io.out.commitCount.valid := true.B
    io.out.commitCount.bits := commitWidth
  }.otherwise{
    for(i <- 0 until MAX_COMMIT_WB){ 
      io.out.commit_wb(i) := 0.U.asTypeOf(new RobCommitWbEntry)
    }
    for(i <- 0 until MAX_COMMIT_STORE){ 
      io.out.commit_store(i) := 0.U.asTypeOf(new RobCommitStoreEntry)
    }
    io.out.commitCount.valid := false.B
    io.out.commitCount.bits := 0.U
  }

  io.out.tail := tail
}



class RobIndexAllocator extends Module {
  val io = IO(new RobIndexAllocatorIO)

  // 环形缓冲指针：使用 ROB_IDX_WIDTH + 1 位（含 wrap 位）
  val headPtr = RegInit(0.U((ROB_IDX_WIDTH + 1).W))
  val tailPtr = RegInit(0.U((ROB_IDX_WIDTH + 1).W))

  // 当前分配和提交数量
  val allocCount  = PopCount(io.in.allocateValid)
  val commitCount = Mux(io.in.commitCount.valid,io.in.commitCount.bits,0.U)

  // ROB 剩余空间计算（考虑 wrap）
  val freeCount = Wire(UInt((ROB_IDX_WIDTH + 1).W))
  when(tailPtr >= headPtr) {
    freeCount := ROB_SIZE.U - (tailPtr - headPtr)
  } .otherwise {
    freeCount := headPtr - tailPtr
  }

  // 是否已满
  io.out.isFull := freeCount < allocCount

  // 分配编号输出（截断掉 wrap 位）
  
  for (i <- 0 until ISSUE_WIDTH) {
    val count = PopCount(io.in.allocateValid.slice(0, i + 1))
    io.out.allocateIdx(i) := Mux(io.in.allocateValid(i), (tailPtr + count)(ROB_IDX_WIDTH - 1, 0), 0.U)
  }

  // 指针更新

  when(io.in.rollback.valid) {
    tailPtr := io.in.rollback.bits
  } .elsewhen(!io.out.isFull && !io.in.stall) {
    tailPtr := tailPtr + allocCount
  }

  when(!io.in.rollback.valid){
    headPtr := headPtr + commitCount
  }
}
