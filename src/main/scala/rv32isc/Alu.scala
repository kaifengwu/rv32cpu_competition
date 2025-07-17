package rv32isc
import chisel3._
import chisel3.util._
import bundles._
import config.Configs._
import config.InstructionConstants._

class Alu extends Module{
  val io = IO(new ALUIO)

  val op     = io.in.alu_ctrl.aluOp
  val src1   = Mux(io.in.alu_ctrl.alu_isAuipc,io.in.pc,Mux(io.in.alu_ctrl.alu_islui,0.U,io.in.rs1_data))
  val src2   = Mux(io.in.alu_ctrl.aluSrc,io.in.imm,io.in.rs2_data)
  val aluUnsigned = io.in.alu_ctrl.aluUnsigned
  val valid  = io.in.valid

  val result = WireDefault(0.U(DATA_WIDTH.W))
  val cmp    = WireDefault(false.B)

  switch(op) {
    is(OP_ADD)  { result := src1 + src2 }
    is(OP_SUB)  { result := src1 - src2 }
    is(OP_AND)  { result := src1 & src2 }
    is(OP_OR)   { result := src1 | src2 }
    is(OP_XOR)  { result := src1 ^ src2 }
    is(OP_SLL)  { result := src1 << src2(4,0) }
    is(OP_SRL)  { result := src1 >> src2(4,0) }
    is(OP_SRA)  { result := (src1.asSInt >> src2(4,0)).asUInt }
    is(OP_NOP)  { result := 0.U }
    // 不再处理分支、跳转、访存指令
  }

  val busy = valid && !io.out_ready // 输出有效但下游未准备好时为busy
  val outValid = valid //输出有效信号 

  io.out.result   := result
  io.out.cmp      := false.B
  io.out.zero     := result === 0.U


  io.out.outValid := outValid   // 输出是否有效，给下游流水级判断ALU的输出信号是否能够使用
  io.out.busy     := busy    // 新增busy信号
}

