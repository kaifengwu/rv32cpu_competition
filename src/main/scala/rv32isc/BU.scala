package rv32isc
import chisel3._
import chisel3.util._
import bundles._
import config.Configs._
import config.InstructionConstants._

// BU模块：处理分支、jal、jalr跳转逻辑
class BU extends Module {
  val io = IO(new BU_IO)

  // 组合逻辑输入
  val src1 = io.in.rs1_data
  val src2 = io.in.rs2_data
  val pc   = io.in.pc
  val imm  = io.in.imm
  val op   = io.in.alu_ctrl.aluOp
  val aluUnsigned = io.in.alu_ctrl.aluUnsigned

  val cmp        = WireDefault(false.B)
  val jumpTarget = WireDefault(0.U(DATA_WIDTH.W))
  val isJump     = WireDefault(false.B)
  val isJalr     = WireDefault(false.B)
  val isBranch   = WireDefault(false.B)
  val actualTaken = WireDefault(false.B)

  switch(op) {
    is(OP_EQ)  { cmp := src1 === src2; isBranch := true.B }
    is(OP_NEQ) { cmp := src1 =/= src2; isBranch := true.B }
    is(OP_LT)  { cmp := Mux(aluUnsigned, src1 < src2, src1.asSInt < src2.asSInt); isBranch := true.B }
    is(OP_GE)  { cmp := Mux(aluUnsigned, src1 >= src2, src1.asSInt >= src2.asSInt); isBranch := true.B }
    is(OP_JAL)  { cmp := true.B; isJump := true.B }
    is(OP_JALR) { cmp := true.B; isJump := true.B; isJalr := true.B }
    is(OP_NOP)  { cmp := false.B }
  }

  when(isBranch) {
    actualTaken := cmp
    jumpTarget := pc + imm
  }.elsewhen(isJump && !isJalr) {
    actualTaken := true.B
    jumpTarget := pc + imm
  }.elsewhen(isJalr) {
    actualTaken := true.B
    jumpTarget := src1 + imm
  }

  // 输出握手协议
  val outValid = true.B // 单周期实现，每周期都能输出
  val busy = outValid && !io.ready // 有效输出但下游未采纳时为busy
  val mispredict = actualTaken =/= io.predictedTaken

  io.out.cmp        := cmp
  io.out.result     := jumpTarget
  io.out.outValid   := outValid
  io.out.isJump     := isJump
  io.out.isJalr     := isJalr
  io.out.isBranch   := isBranch
  io.out.mispredict := mispredict
  io.out.busy       := busy

  io.busy           := busy
}