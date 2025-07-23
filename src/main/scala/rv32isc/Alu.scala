
package rv32isc

import chisel3._
import chisel3.util._
import bundles._
import config.Configs._
import config.InstructionConstants._
import config.OoOParams._

//alu的顶层模块名字叫ALU_Top,实例化的时候实例化这个

class Alu extends Module {
  val io = IO(new ALUIO_Decoupled)

  // 从AluIssueEntry提取操作数和控制信号
  val op = io.in.bits.aluCtrl.aluOp
  val src1 = Mux(io.in.bits.aluCtrl.aluIsAuipc, 
                io.in.bits.pc,
                Mux(io.in.bits.aluCtrl.aluIsLui, 0.U, io.in.bits.rs1data))
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
  io.out.bits.phyRd := io.in.bits.phyRd  // 目标物理寄存器编号
  io.out.bits.busy := busy
  io.out.bits.robIdx := io.in.bits.robIdx // 对应ROB项目编号

  // 旁路输出 - 在组合逻辑阶段直接输出
  io.bypassBus.valid := valid
  io.bypassBus.reg.phyDest := io.in.bits.phyRd
  io.bypassBus.reg.robIdx := io.in.bits.robIdx
  io.bypassBus.data := result

  // 专门的写回旁路总线
  io.writebackBus.valid := valid
  io.writebackBus.reg.phyDest := io.in.bits.phyRd
  io.writebackBus.reg.robIdx := io.in.bits.robIdx
  io.writebackBus.data := result

  // 处理输入就绪信号 - 当ALU不忙时，可以接收新指令
  io.in.ready := !busy
}

// ALU顶层模块，整合RS_alu_Reg、Alu和ALU_WB_Reg
class ALU_Top extends Module {
  val io = IO(new Bundle {
    // 从保留站接收指令
    val in = Flipped(Decoupled(new AluIssueEntry))

    // 写回接口，这个不确定有没有必要。
     val rob_wb = Output(ValidIO(new RobWritebackEntry))

    // 旁路和写回总线
    val bypassBus = Output(new BypassBus)
    val writebackBus = Output(new WritebackBus)

    // 控制信号
    val stall = Input(Bool())
    val flush = Input(Bool())

    // 回滚信号
    val rollback = Input(Valid(new RsRollbackEntry))
  })

  // 实例化RS_alu_Reg
  val rs_alu_reg = Module(new RS_alu_Reg)
  rs_alu_reg.io.in <> io.in
  rs_alu_reg.io.stall := io.stall
  rs_alu_reg.io.flush := io.flush
  rs_alu_reg.io.rollback := io.rollback

  // 实例化ALU
  val alu = Module(new Alu)
  alu.io.in <> rs_alu_reg.io.out

  // 实例化ALU_WB_Reg
  val alu_wb_reg = Module(new ALU_WB_Reg)
  alu_wb_reg.io.in <> alu.io.out
  alu_wb_reg.io.stall := io.stall
  alu_wb_reg.io.flush := io.flush
  alu_wb_reg.io.rollback := io.rollback

  // 连接写回接口
   io.rob_wb <> alu_wb_reg.io.rob_wb

  // 在ALU组合逻辑阶段输出Bypass旁路信号
  io.bypassBus := alu.io.bypassBus

  // 从ALU_WB_Reg输出WritebackBus信号
  // 使用rob_wb的valid信号作为writebackBus的valid信号，因为ALU_OUT没有valid字段
  io.writebackBus.valid := alu_wb_reg.io.rob_wb.valid
  io.writebackBus.reg.phyDest := alu_wb_reg.io.out.phyRd
  io.writebackBus.reg.robIdx := alu_wb_reg.io.out.robIdx
  io.writebackBus.data := alu_wb_reg.io.out.result
}
