package rv32isc
import chisel3._
import chisel3.util._
import bundles._
import config.Configs._
import config.OoOParams._
import config.InstructionConstants._

// BU模块：处理分支、jal、jalr跳转逻辑
class BU extends Module {
  val io = IO(new Bundle {
    val rs1_data = Input(UInt(DATA_WIDTH.W))
    val rs2_data = Input(UInt(DATA_WIDTH.W))
    val pc       = Input(UInt(ADDR_WIDTH.W))
    val imm      = Input(UInt(DATA_WIDTH.W))
    val aluCtrl  = Input(new AluCtrlBundle) // 只用分支相关字段
    val predictedTaken = Input(Bool())
    val robIdx   = Input(UInt(ROB_IDX_WIDTH.W))
    val out      = Output(new BU_OUT)
    val out_ready = Input(Bool()) // 下游是否准备好接收数据
    val busy     = Output(Bool())
    val isReturn = Input(Bool()) // 是否是返回指令
  })

  val src1 = io.rs1_data
  val src2 = io.rs2_data
  val pc   = io.pc
  val imm  = io.imm
  val aluOp        = io.aluCtrl.aluOp
  val aluUnsigned  = io.aluCtrl.aluUnsigned

  val cmp        = WireDefault(false.B)
  val jumpTarget = WireDefault(0.U(ADDR_WIDTH.W))
  // B型指令实际跳转地址
  val branch_actual_target = Wire(UInt(ADDR_WIDTH.W))
  val isJump     = WireDefault(false.B)
  val isJalr     = WireDefault(false.B)
  val isBranch   = WireDefault(false.B)
  val actualTaken = WireDefault(false.B)
  val branch_pc  = WireDefault(0.U(ADDR_WIDTH.W))
  val jal_pc     = WireDefault(0.U(ADDR_WIDTH.W))
  val jal_pc4    = WireDefault(0.U(ADDR_WIDTH.W))


  switch(aluOp) {
    is(OP_EQ)  { cmp := src1 === src2; isBranch := true.B; branch_pc := pc }
    is(OP_NEQ) { cmp := src1 =/= src2; isBranch := true.B; branch_pc := pc }
    is(OP_LT)  { cmp := Mux(aluUnsigned, src1 < src2, src1.asSInt < src2.asSInt); isBranch := true.B; branch_pc := pc }
    is(OP_GE)  { cmp := Mux(aluUnsigned, src1 >= src2, src1.asSInt >= src2.asSInt); isBranch := true.B; branch_pc := pc }
    is(OP_JAL)  { cmp := true.B; isJump := true.B; jal_pc := pc; jal_pc4 := pc + 4.U }
    is(OP_JALR) { cmp := true.B; isJump := true.B; isJalr := true.B; jal_pc := pc; jal_pc4 := pc + 4.U }
    is(OP_NOP)  { cmp := false.B }
  }

  when(isBranch) {
    actualTaken := cmp
    jumpTarget := pc + imm
    branch_actual_target := Mux(actualTaken,pc + imm, pc + 4.U) // 如果跳转被预测为实际跳转，则使用pc + imm，否则使用pc + 4
  }.elsewhen(isJump && !isJalr) {
    actualTaken := true.B
    jumpTarget := pc + imm
  }.elsewhen(isJalr) {
    actualTaken := true.B
    jumpTarget := src1 + imm
  }

  val outValid = true.B // 单周期实现，每周期都能输出
  val busy = outValid && !io.out_ready // 有效输出但下游未采纳时为busy
  val mispredict = actualTaken =/= io.predictedTaken

  io.out.cmp        := cmp
  io.out.result     := jumpTarget
  io.out.outValid   := outValid
  io.out.isBranch   := isBranch
  io.out.mispredict := mispredict
  io.out.busy       := busy
  io.out.branch_pc  := branch_pc
  io.out.jal_pc     := jal_pc
  io.out.jal_pc4    := jal_pc4
  io.out.robIdx     := io.robIdx
  io.out.branch_actual_target := branch_actual_target // 新增输出
  io.out.isReturnOut := io.isReturn // 传递是否是返回指令
  io.busy := busy
}