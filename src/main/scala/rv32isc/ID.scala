package rv32isc
import chisel3._
import chisel3.util._

import bundles._
import config.Configs._
import config.InstructionConstants._

class ID extends Module {
  val io = IO(new IDIO)

  val decoder     = Module(new Decoder)
  val control     = Module(new Control)
  val id_ex_regs  = Seq.fill(FETCH_WIDTH)(Module(new ID_EX_Reg))

  // === 提前识别哪一条是 ret 指令 ===
  val isRetVec = Wire(Vec(FETCH_WIDTH, Bool()))

  for (i <- 0 until FETCH_WIDTH) {
    val inst = io.in.ifVec(i).inst
    isRetVec(i) := inst === "h00008067".U
  }
  val flushByRet = Wire(Vec(FETCH_WIDTH, Bool()))
  for (i <- 0 until FETCH_WIDTH) {
    flushByRet(i) := VecInit(isRetVec.slice(0, i + 1)).asUInt.orR
  }


  // === 连接模块 ===
  for (i <- 0 until FETCH_WIDTH) {
    decoder.io.in.insts(i) := io.in.ifVec(i).inst

    control.io.in.funct3(i) := decoder.io.out.func(i).funct3
    control.io.in.funct7(i) := decoder.io.out.func(i).funct7
    control.io.in.opcode(i) := decoder.io.out.func(i).opcode
    control.io.in.isJump(i) := io.in.ifVec(i).isJump


    id_ex_regs(i).io.flush := io.in.flush || flushByRet(i)
    id_ex_regs(i).io.stall := io.in.stall

    id_ex_regs(i).io.in.ctrl.aluCtrl := control.io.out.aluCtrl(i)
    id_ex_regs(i).io.in.ctrl.brCtrl  := control.io.out.brCtrl(i)
    id_ex_regs(i).io.in.ctrl.wbCtrl  := control.io.out.wbCtrl(i)
    id_ex_regs(i).io.in.ctrl.memCtrl := control.io.out.memCtrl(i)

    id_ex_regs(i).io.in.pc    := io.in.ifVec(i).pc
    id_ex_regs(i).io.in.func3 := decoder.io.out.func(i).funct3
    id_ex_regs(i).io.in.imm   := decoder.io.out.imm(i)
    id_ex_regs(i).io.in.regs  := decoder.io.out.regs(i)

    id_ex_regs(i).io.in.useRs1 := control.io.out.useRs1(i)
    id_ex_regs(i).io.in.useRs2 := control.io.out.useRs2(i)
    id_ex_regs(i).io.in.isRet := isRetVec(i)
    id_ex_regs(i).io.in.isBubble := io.in.ifVec(i).isBubble

    id_ex_regs(i).io.in.jumpTarget := Mux(isRetVec(i),io.in.retTarget.bits,io.in.ifVec(i).jumpTarget)
    id_ex_regs(i).io.in.ctrl.brCtrl.isJump := Mux(isRetVec(i), true.B, control.io.out.brCtrl(i).isJump)

    io.out.idVec(i) := id_ex_regs(i).io.out
    io.out.isRet(i) := isRetVec(i)
  }
  for (i <- 0 until FETCH_WIDTH) {
    io.out.ToRAS.pushReqVec(i).valid := control.io.out.brCtrl(i).isJal || control.io.out.brCtrl(i).isJalr || decoder.io.out.regs(i).rd =/= 0.U // jal/jalr指令或rd不为0的指令进行压栈     
    io.out.ToRAS.pushReqVec(i).bits := io.in.ifVec(i).pc + 4.U // 压栈地址为当前指令地址+4
    io.out.ToRAS.pushReqVec(i).valid := control.io.out.brCtrl(i).isBranch //branch指令进行快照
  }
  io.out.ToRAS.popValid := isRetVec.asUInt.orR
}
