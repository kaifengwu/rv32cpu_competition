package rv32isc
import chisel3._
import chisel3.util._

import bundles._
import config.Configs._
import config.InstructionConstants._

class MEM_WB_Reg extends Module {
  val io = IO(new MEM_WB_IO)

  val mem_wb_reg = RegInit(0.U.asTypeOf(io.out.cloneType)) // 存储 MEM → WB 的数据
  val bubble = RegInit(true.B) // 冒险信号

  when (io.in.flush) {
    mem_wb_reg := 0.U.asTypeOf(io.out.cloneType)  // 清空为 NOP
    mem_wb_reg.pcPlus4 := io.in.mem_data.pcPlus4 // 传递 PC+4
  } .elsewhen (!io.in.stall) {
    mem_wb_reg := io.in.mem_data                  // 正常写入
    bubble := io.in.bubble // 传递冒险信号
  } .otherwise{
    bubble := bubble // 保持冒险信号
   }// stall 时保持原值
  io.out := mem_wb_reg
  io.bubble := bubble // 输出冒险信号
}