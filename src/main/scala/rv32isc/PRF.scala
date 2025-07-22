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

  // === 写回路径：旁路广播 ===
  for (i <- 0 until NUM_BYPASS_PORTS) {
    val wb = io.in.write(i)
    when(wb.valid && wb.reg.phyDest =/= 0.U) {
      regfile(wb.reg.phyDest) := wb.data
      valid(wb.reg.phyDest)   := true.B
    }
  }

  // === 清除新分配目标 valid 位 ===
  for (i <- 0 until ISSUE_WIDTH) {
    when(io.in.alloc(i).valid && io.in.alloc(i).bits =/= 0.U) {
      valid(io.in.alloc(i).bits) := false.B
    }
  }

  // === 读取通道 ===
  for (i <- 0 until ISSUE_WIDTH) {
    val rs1Idx = io.in.readRS1(i).bits
    val rs2Idx = io.in.readRS2(i).bits

    val rs1Value = WireDefault(0.U(DATA_WIDTH.W))
    val rs2Value = WireDefault(0.U(DATA_WIDTH.W))
    val rs1Ready = WireDefault(false.B)
    val rs2Ready = WireDefault(false.B)

    // === 是否刚刚分配 ===
    val rs1JustAllocated = io.in.alloc.zipWithIndex.take(i + 1)
      .map { case (a, j) => a.valid && (a.bits === rs1Idx) && rs1Idx =/= 0.U }
      .reduce(_ || _)

    val rs2JustAllocated = io.in.alloc.zipWithIndex.take(i + 1)
      .map { case (a, j) => a.valid && (a.bits === rs2Idx) && rs2Idx =/= 0.U }
      .reduce(_ || _)

    when(io.in.readRS1(i).valid) {
      when(rs1Idx === 0.U) {
        rs1Value := 0.U
        rs1Ready := true.B
      }.elsewhen(valid(rs1Idx) && !rs1JustAllocated) {
        rs1Value := regfile(rs1Idx)
        rs1Ready := true.B
      }

      for (j <- 0 until NUM_BYPASS_PORTS) {
        val bp = io.in.write(j)
        when(bp.valid && !rs1Ready && bp.reg.phyDest === rs1Idx && rs1Idx =/= 0.U) {
          rs1Value := bp.data
          rs1Ready := true.B
        }
      }
    }

    when(io.in.readRS2(i).valid) {
      when(rs2Idx === 0.U) {
        rs2Value := 0.U
        rs2Ready := true.B
      }.elsewhen(valid(rs2Idx) && !rs2JustAllocated) {
        rs2Value := regfile(rs2Idx)
        rs2Ready := true.B
      }

      for (j <- 0 until NUM_BYPASS_PORTS) {
        val bp = io.in.write(j)
        when(bp.valid && !rs2Ready && bp.reg.phyDest === rs2Idx && rs2Idx =/= 0.U) {
          rs2Value := bp.data
          rs2Ready := true.B
        }
      }
    }

    io.out.readRS1Data(i)  := Mux(rs1Ready, rs1Value, 0.U)
    io.out.readRS1Ready(i) := rs1Ready

    io.out.readRS2Data(i)  := Mux(rs2Ready, rs2Value, 0.U)
    io.out.readRS2Ready(i) := rs2Ready
  }
}
