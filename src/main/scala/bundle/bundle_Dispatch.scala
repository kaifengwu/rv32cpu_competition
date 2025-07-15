package bundles

import chisel3._
import chisel3.util._
import config.OoOParams._
import config.Configs._

class DispatchStageIO extends Bundle {
  val in = Input(new Bundle {
    val renameVec = Vec(ISSUE_WIDTH, new RenameBundle)
    val isRet     = Vec(ISSUE_WIDTH, Bool())
  })

  val out = new Bundle {
    // 发往 ALU Reservation Station（每个 ALU 一个入口）
    val aluEnq = Vec(ALU_UNITS, Decoupled(new AluIssueEntry))

    // 发往 BR Reservation Station（每个 BR 一个入口）
    val brEnq = Vec(BR_UNITS, Decoupled(new BrIssueEntry))

    // 发往 LSU Reservation Station（每个 LSU 一个入口）
    val lsuEnq = Vec(LSU_UNITS, Decoupled(new LsuIssueEntry))

    // 分配到 ROB 的端口（与 ISSUE_WIDTH 对齐）
    val robEnq = Vec(ISSUE_WIDTH, Decoupled(new RobEntry))

    // 通知 RAT 更新（用于回滚或 checkpoint）
    val ratUpdate = Vec(ISSUE_WIDTH, new Bundle {
      val logicRd  = UInt(ARCH_REG_IDX_WIDTH.W)
      val phyRd    = UInt(PHYS_REG_IDX_WIDTH.W)
      val oldPhyRd = UInt(PHYS_REG_IDX_WIDTH.W)
      val valid    = Bool()
    })

    // 资源不足时全局 stall
    val stall = Output(Bool())
  }
}


class AluIssueEntry extends Bundle {
  val robIdx    = UInt(ROB_IDX_WIDTH.W)
  val phyRd     = UInt(PHYS_REG_IDX_WIDTH.W)
  val phyRs1    = UInt(PHYS_REG_IDX_WIDTH.W)
  val phyRs2    = UInt(PHYS_REG_IDX_WIDTH.W)
  val imm       = UInt(DATA_WIDTH.W)
  val aluCtrl   = new AluCtrlBundle
  val valid     = Bool()
  val useRs1    = Bool()
  val useRs2    = Bool()
}

class BrIssueEntry extends Bundle {
  val robIdx    = UInt(ROB_IDX_WIDTH.W)
  val phyRs1    = UInt(PHYS_REG_IDX_WIDTH.W)
  val phyRs2    = UInt(PHYS_REG_IDX_WIDTH.W)
  val imm       = UInt(DATA_WIDTH.W)
  val brCtrl    = new BranchCtrlBundle
  val valid     = Bool()
  val useRs1    = Bool()
  val useRs2    = Bool()
}

class LsuIssueEntry extends Bundle {
  val robIdx    = UInt(ROB_IDX_WIDTH.W)
  val phyRs1    = UInt(PHYS_REG_IDX_WIDTH.W) // 地址基址
  val phyRs2    = UInt(PHYS_REG_IDX_WIDTH.W) // store 写入数据
  val imm       = UInt(DATA_WIDTH.W)
  val memCtrl   = new MemCtrlBundle
  val valid     = Bool()
  val useRs1    = Bool()
  val useRs2    = Bool()
}

class RobEntry extends Bundle {
  val valid    = Bool()
  val isRet    = Bool()  // 是否为 ret，用于重定向
  val isLoad   = Bool()
  val isStore  = Bool()
  val isBranch = Bool()

  val logicRd  = UInt(ARCH_REG_IDX_WIDTH.W)   // 写回目标逻辑寄存器
  val phyRd    = UInt(PHYS_REG_IDX_WIDTH.W)   // 分配的新物理寄存器
  val oldPhyRd = UInt(PHYS_REG_IDX_WIDTH.W)   // 被覆盖的旧物理寄存器（用于回滚）

  val robIdx   = UInt(ROB_IDX_WIDTH.W)        // 自身在 ROB 中的编号
  val completed = Bool()                      // 执行是否完成
  val exception = Bool()                      // 是否异常
}

class AluReservationStationIO extends Bundle {
  val in = Flipped(Decoupled(new AluIssueEntry)) // 派遣阶段传入的指令
  val bypass = Input(Vec(NUM_BYPASS_PORTS, new BypassBus)) // 广播前馈数据总线

  val out = Decoupled(new AluIssueEntry) // 发往 ALU 执行单元的指令
}
