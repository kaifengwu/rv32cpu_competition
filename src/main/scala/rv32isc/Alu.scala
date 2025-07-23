
package rv32isc

import chisel3._
import chisel3.util._
import bundles._
import config.Configs._
import config.InstructionConstants._
import config.OoOParams._

class Alu extends Module {
  val io = IO(new ALUIO_Decoupled)

  // 从AluIssueEntry提取操作数和控制信号
  val op = io.in.bits.aluCtrl.aluOp
  val src1 = Mux(io.in.bits.aluCtrl.alu_isAuipc, 
                io.in.bits.pc,
                Mux(io.in.bits.aluCtrl.alu_islui, 0.U, io.in.bits.rs1data))
  val src2 = Mux(io.in.bits.aluCtrl.aluSrc, io.in.bits.imm, io.in.bits.rs2data)
  val aluUnsigned = io.in.bits.aluCtrl.aluUnsigned
  val valid = io.in.valid  // 指令有效性由RS_alu_Reg保证（已经过滤回滚区间内的指令）

  val result = WireDefault(0.U(DATA_WIDTH.W))
  val cmp = WireDefault(false.B)

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

  val busy = valid // 输出有效时为busy

  // 将结果连接到输出
  io.out.valid := valid
  io.out.bits.result := result
  io.out.bits.cmp := false.B
  io.out.bits.zero := result === 0.U
  io.out.bits.busy := busy
  io.out.bits.phyRd := io.in.bits.phyRd  // 目标物理寄存器编号
  io.out.bits.robIdx := io.in.bits.robIdx // 对应ROB项目编号

  // 旁路输出 - 在组合逻辑阶段直接输出
  io.bypassBus.valid := valid
  io.bypassBus.reg.phyDest := io.in.bits.phyRd
  io.bypassBus.reg.robIdx := io.in.bits.robIdx
  io.bypassBus.data := result

  // 处理输入就绪信号 - 当ALU不忙时，可以接收新指令
  io.in.ready := !busy
}
