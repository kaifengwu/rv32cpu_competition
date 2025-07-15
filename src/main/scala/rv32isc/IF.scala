package rv32isc
import chisel3._
import chisel3.util._

import bundles._
import config.Configs._
import config.InstructionConstants._

class IFStage extends Module {
  val io = IO(new IFStageIO)

  // === 子模块实例化 ===
  val pcReg     = Module(new PCReg)
  val predictor = Module(new BranchPredictor)
  val if_id_regs = Seq.fill(FETCH_WIDTH)(Module(new IF_ID_Reg))

  // === 仿真用指令 ROM ===
  val instMem = Mem(MEM_INST_SIZE / 4, UInt(INST_WIDTH.W))

  // === 连接 PCReg ===
  pcReg.io.in.redirect := predictor.io.out.redirect
  pcReg.io.in.stall    := io.in.stall

  // === 连接分支预测器 ===
  predictor.io.in.pcVec  := pcReg.io.out.pcVec
  predictor.io.in.update := io.in.update

  // === 指令读取 ===
  val instVec = Wire(Vec(FETCH_WIDTH, UInt(INST_WIDTH.W)))
  for (i <- 0 until FETCH_WIDTH) {
    instVec(i) := instMem(pcReg.io.out.pcVec(i) >> 2)
  }

  // === 构造 IFBundle ===
  val IFBundleVec = Wire(Vec(FETCH_WIDTH, new IFBundle))
  val redirectValid  = predictor.io.out.redirect.valid

  for (i <- 0 until FETCH_WIDTH) {
    IFBundleVec(i).pc         := pcReg.io.out.pcVec(i)
    IFBundleVec(i).inst       := instVec(i)
  }

  // === IF/ID Reg 连接 ===
  for (i <- 0 until FETCH_WIDTH) {
    val flushThis = io.in.flush || predictor.io.out.maskAfterRedirect(i)
    if_id_regs(i).io.in    := IFBundleVec(i)
    if_id_regs(i).io.stall := io.in.stall
    if_id_regs(i).io.flush := flushThis
  }

  // === 输出到 Decode ===
  io.out.toDecode := VecInit(if_id_regs.map(_.io.out))
}
