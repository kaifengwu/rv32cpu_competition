package bundles

import chisel3._
import chisel3.util._
import config.OoOParams._
import config.Configs._

class BrIssueEntry extends Bundle {
  val robIdx = UInt(ROB_IDX_WIDTH.W)

  val phyRd   = UInt(PHYS_REG_IDX_WIDTH.W)     // 写回目的寄存器（仅 jal/jalr）
  
  val useRs1   = Bool()
  val phyRs1   = UInt(PHYS_REG_IDX_WIDTH.W)      // 源寄存器1
  val rs1Ready = Bool()                          // 是否就绪
  val rs1data  = UInt(DATA_WIDTH.W)

  val useRs2   = Bool()
  val phyRs2   = UInt(PHYS_REG_IDX_WIDTH.W)      // 源寄存器2
  val rs2Ready = Bool()                          // 是否就绪
  val rs2data  = UInt(DATA_WIDTH.W)

  val func3   = UInt(3.W)                      // 分支判断控制
  val imm     = UInt(DATA_WIDTH.W)             // 跳转偏移
  val pc      = UInt(ADDR_WIDTH.W)             // 指令所在 PC

  val isBranch = Bool()                        // 是否为条件分支指令（beq/bne/...）
  val isJal    = Bool()                        // 是否为 jal
  val isJalr   = Bool()                        // 是否为 jalr
  val PredictTarget = UInt(ADDR_WIDTH.W) // 预测跳转目标地址
}

class BrRSIO extends Bundle {
  val in = new Bundle {
    val enq    = Input(Vec(ISSUE_WIDTH, ValidIO(new BrIssueEntry)))  // 多发射入队
    val bypass = Input(Vec(NUM_BYPASS_PORTS, new BypassBus))             // 前馈广播
    val rollback = Input(ValidIO(new RsRollbackEntry))
  }

  val out = new Bundle {
    val issue = Vec(BR_UNITS, Decoupled(new BrIssueEntry))   // 发射口（支持多个 BR 执行单元）
    val freeEntryCount = Output(UInt(log2Ceil(ISSUE_WIDTH + 1).W))//剩余端口数
  }
}
