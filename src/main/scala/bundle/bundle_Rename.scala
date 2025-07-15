package bundles

import chisel3._
import chisel3.util._
import config.Configs._
import config.OoOParams._

class RenameBundle extends Bundle {
  val valid = Bool()

  val phyRs1 = UInt(PHYS_REG_IDX_WIDTH.W)
  val phyRs2 = UInt(PHYS_REG_IDX_WIDTH.W)
  val phyRd = UInt(PHYS_REG_IDX_WIDTH.W)
  val oldPhyRd = UInt(PHYS_REG_IDX_WIDTH.W)

  val robIdx = UInt(ROB_IDX_WIDTH.W)

  val logicRegs = new RegBundle
  val imm = UInt(DATA_WIDTH.W)
  val func3 = UInt(3.W)

  val ctrl = new Bundle {
    val aluCtrl = new AluCtrlBundle
    val memCtrl = new MemCtrlBundle
    val wbCtrl = new WbCtrlBundle
    val brCtrl = new BranchCtrlBundle
  }

  val useRs1 = Bool()
  val useRs2 = Bool()
  val isRet = Bool() // ★ 添加：是否是 ret 伪指令
}

class RenameStageIO extends Bundle {
  val in = new Bundle {
    val idVec = Input(Vec(ISSUE_WIDTH, new IDBundle)) // 来自 ID 阶段的解码信息
    val isRet = Input(Vec(ISSUE_WIDTH, Bool())) // 每条指令是否是 ret
    val dealloc = Input(Vec(COMMIT_WIDTH, Flipped(ValidIO(UInt(PHYS_REG_IDX_WIDTH.W)))))
    val stall = Input(Bool())
    val flush = Input(Bool())
  }

  val out = new Bundle {
    val renameVec = Output(Vec(ISSUE_WIDTH, new RenameBundle)) // 输出重命名结果
    val isRet = Input(Vec(ISSUE_WIDTH, Bool())) // 每条指令是否是 ret
  }
}

class RATIO extends Bundle {
  val in = new Bundle {
    val logicRs1 = Input(Vec(ISSUE_WIDTH, UInt(ARCH_REG_IDX_WIDTH.W)))
    val logicRs2 = Input(Vec(ISSUE_WIDTH, UInt(ARCH_REG_IDX_WIDTH.W)))

    val wen      = Input(Vec(ISSUE_WIDTH, Bool()))
    val logicRd  = Input(Vec(ISSUE_WIDTH, UInt(ARCH_REG_IDX_WIDTH.W)))
    val phyRd    = Input(Vec(ISSUE_WIDTH, UInt(PHYS_REG_IDX_WIDTH.W)))
  }

  val out = new Bundle {
    val phyRs1    = Output(Vec(ISSUE_WIDTH, UInt(PHYS_REG_IDX_WIDTH.W)))
    val phyRs2    = Output(Vec(ISSUE_WIDTH, UInt(PHYS_REG_IDX_WIDTH.W)))
    val oldPhyRd  = Output(Vec(ISSUE_WIDTH, UInt(PHYS_REG_IDX_WIDTH.W))) // 输出旧映射值
  }
}
class FreeListIO extends Bundle {
  val in = new Bundle {
    // 哪些指令需要申请物理寄存器
    val allocate = Input(Vec(ISSUE_WIDTH, Bool()))

    // 哪些物理寄存器要被释放（ValidIO 替代 deallocValid 和 deallocReg）
    val dealloc = Input(Vec(COMMIT_WIDTH, ValidIO(UInt(PHYS_REG_IDX_WIDTH.W))))
  }

  val out = new Bundle {
    // 分配结果
    val phyRd = Output(Vec(ISSUE_WIDTH, UInt(PHYS_REG_IDX_WIDTH.W)))
  }
}

class RenameDispatchRegIO extends Bundle {
  val in = new Bundle {
    val renameVec   = Input(new RenameBundle)
    val stall       = Input(Bool())
    val flush       = Input(Bool())
    val isRet = Bool() // ★ 添加：是否是 ret 伪指令
  }

  val out = new Bundle {
    val renameVec   = Output(new RenameBundle)
    val isRet = Bool() // ★ 添加：是否是 ret 伪指令
  }
}
