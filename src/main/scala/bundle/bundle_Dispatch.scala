package bundles

import chisel3._
import chisel3.util._
import config.OoOParams._
import config.Configs._

class AluIssueEntry extends Bundle {
  val robIdx   = UInt(ROB_IDX_WIDTH.W)           // 对应 ROB 项目编号
  val phyRd    = UInt(PHYS_REG_IDX_WIDTH.W)      // 目标物理寄存器编号

  val phyRs1   = UInt(PHYS_REG_IDX_WIDTH.W)      // 源寄存器1
  val rs1Ready = Bool()                          // 是否就绪

  val useRs1   = Bool()
  val useRs2   = Bool()

  val phyRs2   = UInt(PHYS_REG_IDX_WIDTH.W)      // 源寄存器2
  val rs2Ready = Bool()                          // 是否就绪

  val imm      = UInt(DATA_WIDTH.W)              // 立即数
  val aluCtrl  = new AluCtrlBundle               // ALU 控制信息

  val valid    = Bool()                          // 条目是否有效
}

class AluRSIO extends Bundle {
  val in = new Bundle {
    val enq    = Vec(ISSUE_WIDTH,Flipped(Decoupled(new AluIssueEntry))) // 从 Dispatch 发射到 ALU RS
    val bypass = Input(Vec(NUM_BYPASS_PORTS, new BypassBus)) // 前馈广播输入
    val aluReady = Input(Vec(ALU_UNITS, Bool())) // 来自每个 ALU 执行单元的 ready 信号
  }

  val out = new Bundle {
    val issue = Vec(ALU_UNITS, Decoupled(new AluIssueEntry)) // 发射给多个 ALU 执行单元
  }
}

