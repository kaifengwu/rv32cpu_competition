package bundles

import chisel3._
import chisel3.util._
import config.OoOParams._
import config.Configs._

class AluIssueEntry extends Bundle {
  val robIdx   = UInt(ROB_IDX_WIDTH.W)           // 对应 ROB 项目编号
  val phyRd    = UInt(PHYS_REG_IDX_WIDTH.W)      // 目标物理寄存器编号

  val useRs1   = Bool()
  val phyRs1   = UInt(PHYS_REG_IDX_WIDTH.W)      // 源寄存器1
  val rs1Ready = Bool()                          // 是否就绪
  val rs1data  = UInt(DATA_WIDTH.W)

  val useRs2   = Bool()
  val phyRs2   = UInt(PHYS_REG_IDX_WIDTH.W)      // 源寄存器2
  val rs2Ready = Bool()                          // 是否就绪
  val rs2data  = UInt(DATA_WIDTH.W)

  val imm      = UInt(DATA_WIDTH.W)              // 立即数
  val pc       = UInt(DATA_WIDTH.W)
  val aluCtrl  = new AluCtrlBundle               // ALU 控制信息
}


class RsRollbackEntry extends Bundle {
  val rollbackIdx = UInt(ROB_IDX_WIDTH.W) // 回滚目标
  val tailIdx     = UInt(ROB_IDX_WIDTH.W) // 当前 ROB tail
}

class AluRSIO extends Bundle {
  val in = new Bundle {
    val enq    = Input(Vec(ISSUE_WIDTH,ValidIO(new AluIssueEntry)))// 从 Dispatch 发射到 ALU RS
    val bypass = Input(Vec(NUM_BYPASS_PORTS, new BypassBus)) // 前馈广播输入
    val rollback = Input(ValidIO(new RsRollbackEntry))
  }

  val out = new Bundle {
    val issue = Vec(ALU_UNITS, Decoupled(new AluIssueEntry)) // 发射给多个 ALU 执行单元
    val freeEntryCount = Output(UInt(log2Ceil(ISSUE_WIDTH + 1).W))//剩余端口数
    val isFull = Output(Bool())
  }
}

