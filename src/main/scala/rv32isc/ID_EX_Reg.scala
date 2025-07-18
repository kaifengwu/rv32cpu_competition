package rv32isc
import chisel3._
import chisel3.util._

import bundles._
import config.Configs._

class ID_EX_Reg extends Module {
  val io = IO(new Bundle {
    val in    = Input(new IDBundle)
    val out   = Output(new IDBundle)
    val stall = Input(Bool())
    val flush = Input(Bool())
  })

  val reg = RegInit(0.U.asTypeOf(new IDBundle))

  when(io.flush) {
    reg := 0.U.asTypeOf(new IDBundle)
  }.elsewhen(!io.stall) {
    reg := io.in
  }

  io.out := reg
}