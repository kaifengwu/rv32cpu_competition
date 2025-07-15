package rv32isc
import chisel3._
import chisel3.util._

import bundles._
import config.Configs._
import config.InstructionConstants._

class BranchPredictor extends Module {
  val io = IO(new BranchPredictorIO)

  // === 结构定义 ===
  val bht = RegInit(VecInit(Seq.fill(BHT_SIZE)(0.U(2.W))))
  val btbValid  = RegInit(VecInit(Seq.fill(BHT_SIZE)(false.B)))
  val btbTag    = RegInit(VecInit(Seq.fill(BHT_SIZE)(0.U((ADDR_WIDTH - BHT_INDEX_WIDTH).W))))
  val btbTarget = RegInit(VecInit(Seq.fill(BHT_SIZE)(0.U(ADDR_WIDTH.W))))

  // === redirect 默认值 ===

  // 初始化
  io.out.redirect.valid := false.B
  io.out.redirect.bits  := 0.U
  io.out.maskAfterRedirect := 0.U

  // 定义向量
  val takenVec    = Wire(Vec(FETCH_WIDTH, Bool()))
  val targetVec   = Wire(Vec(FETCH_WIDTH, UInt(ADDR_WIDTH.W)))
  val btbHitVec   = Wire(Vec(FETCH_WIDTH, Bool()))

  for (i <- 0 until FETCH_WIDTH) {
    val pc    = io.in.pcVec(i)
    val index = pc(11, 2)
    val tag   = pc(ADDR_WIDTH - 1, 12)

    val counter = bht(index)
    val taken   = counter(1) // 第二位表示 taken

    val btbHit  = btbValid(index) && (btbTag(index) === tag)
    val target  = Mux(btbHit, btbTarget(index), pc + 4.U)

    takenVec(i)  := taken && btbHit
    targetVec(i) := target
    btbHitVec(i) := btbHit
  }

  // 优先编码器找第一个有效跳转
  // 检查是否有任何指令被预测为跳转
  val hasTaken  = takenVec.reduce(_ || _)
  // 获取第一个被预测为跳转的指令索引
  val takenIdx  = PriorityEncoder(takenVec)
  // 获取跳转目标地址
  val redirectTarget = targetVec(takenIdx)
  // 构造 mask：从 takenIdx+1 开始为 1
  val mask = Wire(UInt(FETCH_WIDTH.W))
  for(i <- 0 until FETCH_WIDTH) {
    // 构造 mask：从 takenIdx+1 开始为 1
    mask(i) := (takenVec.reverse.drop(FETCH_WIDTH - i).reduceOption(_ || _).getOrElse(false.B))
  }
  //    mask := ((1.U << FETCH_WIDTH) - 1.U) & (~((1.U << (takenIdx + 1.U)) - 1.U))

  // 赋值 redirect 和 mask
  when(hasTaken) {
    io.out.redirect.valid := true.B
    io.out.redirect.bits  := redirectTarget
    io.out.maskAfterRedirect := mask
  }
  io.out.maskAfterRedirect := mask

  // === 更新 BHT / BTB（由 EX 阶段发起） ===
  val updateIndex = io.in.update.pc(11, 2)
  val updateTag   = io.in.update.pc(ADDR_WIDTH - 1, 12)

  when(io.in.update.valid) {
    val old = bht(updateIndex)
    bht(updateIndex) := Mux(io.in.update.taken,
      Mux(old === 3.U, 3.U, old + 1.U),
      Mux(old === 0.U, 0.U, old - 1.U)
    )

    when(io.in.update.taken) { // 如果跳转被预测为 taken
      btbValid(updateIndex)  := true.B // 设置 BTB 有效位
      btbTag(updateIndex)    := updateTag // 更新 BTB 的 tag
      btbTarget(updateIndex) := io.in.update.target // 更新 BTB 的目标地址
    }
  }
}
