package rv32isc
import chisel3._
import chisel3.util._

import bundles._
import config.Configs._

class IF_ID_Reg extends Module {
  val io = IO(new Bundle {
    val in    = Input(new IFBundle)
    val out   = Output(new IFBundle)
    val stall = Input(Bool())
    val flush = Input(Bool())
  })

  val reg = RegInit(0.U.asTypeOf(new IFBundle))

  when(io.flush) {
    reg := 0.U.asTypeOf(new IFBundle)
  }.elsewhen(!io.stall) {
    reg := io.in
  }.otherwise{
    reg := reg // 保持当前状态
  }

  io.out := reg
}
