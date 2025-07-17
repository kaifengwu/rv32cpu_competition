package rv32isc
import chisel3._
import chisel3.util._

import bundles._
import config.Configs._
import config.InstructionConstants._

class EX extends Module{
  val io = IO(new EX_IO)


  val Alu = Seq.fill(FETCH_WIDTH)(Module(new Alu))
  val EX_MEM_regs = Seq.fill(FETCH_WIDTH)(Module(new EX_MEM_Reg))

  //内部数据
  for(i <- 0 until FETCH_WIDTH){ 
    EX_MEM_regs(i).io.in.ex_data.aluOut := Alu(i).io.out
  }
  //外部数据
  for(i <- 0 until FETCH_WIDTH){ 
    //输入数据
    Alu(i).io.in := io.in.data(i).alu_in

    EX_MEM_regs(i).io.in.bubble := io.in.bubble(i) //冒险信号

    EX_MEM_regs(i).io.in.ex_data.mem := io.in.data(i).mem
    EX_MEM_regs(i).io.in.ex_data.ctrl := io.in.data(i).ctrl
    EX_MEM_regs(i).io.in.ex_data.pc  := io.in.data(i).pc
    EX_MEM_regs(i).io.in.ex_data.rd  := io.in.data(i).rd
    EX_MEM_regs(i).io.in.ex_data.funct3  := io.in.data(i).funct3
    EX_MEM_regs(i).io.in.flush := io.in.flush(i)
    EX_MEM_regs(i).io.in.stall := io.in.stall(i)
    EX_MEM_regs(i).io.in.ex_data.rs2_data := io.in.data(i).rs2_data //输出Store类型指令的寄存器数据
    EX_MEM_regs(i).io.in.ex_data.isJalr := io.in.data(i).isJalr // 是否为jalr指令

    //输出数据
    io.out(i) := EX_MEM_regs(i).io.out
    io.bubble(i) := EX_MEM_regs(i).io.bubble
  }
}
