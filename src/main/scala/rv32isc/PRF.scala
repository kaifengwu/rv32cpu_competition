package rv32isc

import chisel3._
import chisel3.util._

import bundles._
import config.Configs._
import config.OoOParams._

class PRF extends Module {
  val io = IO(new PRFBundle)

  val regfile = Reg(Vec(PHYS_REG_NUM, UInt(DATA_WIDTH.W)))
  val valid   = RegInit(VecInit(Seq.fill(PHYS_REG_NUM)(false.B)))

  // === 写回路径 ===
  for (i <- 0 until EXEC_UNITS) {
    when(io.in.write(i).valid && io.in.write(i).bits.addr =/= 0.U) {
      val addr = io.in.write(i).bits.addr
      regfile(addr) := io.in.write(i).bits.data
      valid(addr)   := true.B
    }
  }

  // === 清除新分配目标 valid ===
  for (i <- 0 until ISSUE_WIDTH) {
    when(io.in.allocValid(i) && io.in.allocPhysReg(i) =/= 0.U) {
      valid(io.in.allocPhysReg(i)) := false.B
    }
  }

  // === 读取 RS1 ===
  for (i <- 0 until ISSUE_WIDTH) {
    val addr = io.in.readRS1(i)
    io.out.readRS1Data(i)  := Mux(addr === 0.U, 0.U, regfile(addr))
    io.out.readRS1Valid(i) := Mux(addr === 0.U, true.B, valid(addr))
  }

  // === 读取 RS2 ===
  for (i <- 0 until ISSUE_WIDTH) {
    val addr = io.in.readRS2(i)
    io.out.readRS2Data(i)  := Mux(addr === 0.U, 0.U, regfile(addr))
    io.out.readRS2Valid(i) := Mux(addr === 0.U, true.B, valid(addr))
  }
}
