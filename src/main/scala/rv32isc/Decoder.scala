package rv32isc
import chisel3._
import chisel3.util._

import bundles._
import config.Configs._
import config.InstructionConstants._

class Decoder extends Module {
  val io = IO(new DecoderIO)

  for (i <- 0 until FETCH_WIDTH) {
    val inst = io.in.insts(i)
    io.out.func(i).opcode := inst(6, 0)
    io.out.func(i).funct3 := inst(14, 12)
    io.out.func(i).funct7 := inst(31, 25)

    io.out.regs(i).rd  := inst(11, 7)
    io.out.regs(i).rs1 := inst(19, 15)
    io.out.regs(i).rs2 := inst(24, 20)

    // === Imm 提取 ===
    val opcode = inst(6, 0)
    val immI = Cat(Fill(20, inst(31)), inst(31, 20))
    val immS = Cat(Fill(20, inst(31)), inst(31, 25), inst(11, 7))
    val immB = Cat(Fill(20, inst(31)), inst(7), inst(31, 25), inst(11, 8), 0.U(1.W))
    val immU = Cat(inst(31, 12), Fill(12, 0.U))
    val immJ = Cat(Fill(12, inst(31)), inst(31), inst(19, 12), inst(20), inst(30, 21), Fill(1, 0.U))
    val imm = WireDefault(0.U(32.W))

    switch(opcode) {
      is(OP_I)     { imm := immI }
      is(OP_LOAD)  { imm := immI }
      is(OP_JALR)  { imm := immI }
      is(OP_STORE) { imm := immS }
      is(OP_BRANCH){ imm := immB }
      is(OP_LUI)   { imm := immU }
      is(OP_AUIPC) { imm := immU }
      is(OP_JAL)   { imm := immJ }
      is(OP_SYSTEM){ imm := immI } // CSR 指令的 zimm 可另外提取
    }
    io.out.imm(i) := imm
  }
}
