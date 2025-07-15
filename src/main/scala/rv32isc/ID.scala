package rv32isc
import chisel3._
import chisel3.util._

import bundles._
import config.Configs._
import config.InstructionConstants._

class ID extends Module {
  val io = IO(new IDIO)

  // 子模块
  val decoder = Module(new Decoder)
  val control = Module(new Control)
  val id_ex_regs = Seq.fill(FETCH_WIDTH)(Module(new ID_EX_Reg))


  // === 给 Decoder 输入 ===
  for(i <- 0 until FETCH_WIDTH){
    decoder.io.in.insts(i) := io.in.ifVec(i).inst

    control.io.in.funct3(i) := decoder.io.out.func(i).funct3
    control.io.in.funct7(i) := decoder.io.out.func(i).funct7
    control.io.in.opcode(i) := decoder.io.out.func(i).opcode

    id_ex_regs(i).io.flush := io.in.flush
    id_ex_regs(i).io.stall := io.in.stall
  
    //计算单元控制指令
    id_ex_regs(i).io.in.ctrl.aluCtrl := control.io.out.aluCtrl
    id_ex_regs(i).io.in.ctrl.brCtrl := control.io.out.brCtrl
    id_ex_regs(i).io.in.ctrl.wbCtrl := control.io.out.wbCtrl
    id_ex_regs(i).io.in.ctrl.memCtrl := control.io.out.memCtrl

    id_ex_regs(i).io.in.func3 := decoder.io.out.func(i).funct3
    id_ex_regs(i).io.in.imm := decoder.io.out.imm(i)
    id_ex_regs(i).io.in.regs := decoder.io.out.regs(i)

    id_ex_regs(i).io.in.useRs1 := control.io.out.useRs1(i)
    id_ex_regs(i).io.in.useRs2 := control.io.out.useRs2(i)

    io.out.idVec(i) := id_ex_regs(i).io.out
  }

  for(i <- 0 until FETCH_WIDTH){ 
    io.out.isRet(i) := (io.in.ifVec(i).inst === "h00008067".U)
  }
}
