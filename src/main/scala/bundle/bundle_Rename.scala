package bundles

import chisel3._
import chisel3.util._
import config.Configs._
import config.OoOParams._

class RenameBundle extends Bundle {
  val phyRs1 = UInt(PHYS_REG_IDX_WIDTH.W)
  val phyRs2 = UInt(PHYS_REG_IDX_WIDTH.W)
  val phyRd = UInt(PHYS_REG_IDX_WIDTH.W)
  val oldPhyRd = UInt(PHYS_REG_IDX_WIDTH.W)
  val pc  = UInt(ADDR_WIDTH.W) // 指令所在 PC

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
  val jumpTarget = UInt(ADDR_WIDTH.W)
  val tailPtr = UInt(log2Ceil(FREELIST_SIZE).W) // 回滚目标指针
  val isBubble = Bool()
}

class RenameStageIO extends Bundle {
  val in = new Bundle {
    val idVec = Input(Vec(ISSUE_WIDTH, new IDBundle)) // 来自 ID 阶段的解码信息
    val dealloc = Input(Vec(MAX_COMMIT_WB, ValidIO(UInt(PHYS_REG_IDX_WIDTH.W))))

    val rollbackTail = Input(ValidIO(UInt(log2Ceil(FREELIST_SIZE).W))) // 回滚目标指针
    val rollbackPc = Input(ValidIO(UInt(ADDR_WIDTH.W))) // 回滚目标指针

    val robIdx = Input(Vec(ISSUE_WIDTH, UInt(ROB_IDX_WIDTH.W))) // 分配编号
    val stall = Input(Bool())
    val flush = Input(Bool())
  }

  val out = new Bundle {
    val allocateValid = Output(Vec(ISSUE_WIDTH, Bool())) // 分配RobIdx的有效性标志
    val renameVec = Output(Vec(ISSUE_WIDTH, new RenameBundle)) // 输出重命名结果
  }
}


class RATIO extends Bundle {
  val in = new Bundle {
    // 查表输入
    val stall = Input(Bool()) // 阶段停顿
    val logicRs1 = Input(Vec(ISSUE_WIDTH, UInt(ARCH_REG_IDX_WIDTH.W)))
    val logicRs2 = Input(Vec(ISSUE_WIDTH, UInt(ARCH_REG_IDX_WIDTH.W)))

    // 写入映射表
    val wen     = Input(Vec(ISSUE_WIDTH, Bool()))
    val logicRd = Input(Vec(ISSUE_WIDTH, UInt(ARCH_REG_IDX_WIDTH.W)))
    val phyRd   = Input(Vec(ISSUE_WIDTH, UInt(PHYS_REG_IDX_WIDTH.W)))

    // 快照请求（来自 ISSUE_WIDTH 条分支）
    val snapshot = Input(Vec(ISSUE_WIDTH, ValidIO(UInt(ADDR_WIDTH.W)))) // PC 作为快照标识

    // 回滚请求（ValidIO）
    val rollback = Input(ValidIO(UInt(ADDR_WIDTH.W))) // rollback.valid + rollback.bits
    val commit = Input(ValidIO(UInt(ADDR_WIDTH.W))) // 提交成功的分支 PC
  }

  val out = new Bundle {
    val phyRs1    = Output(Vec(ISSUE_WIDTH, UInt(PHYS_REG_IDX_WIDTH.W)))
    val phyRs2    = Output(Vec(ISSUE_WIDTH, UInt(PHYS_REG_IDX_WIDTH.W)))
    val oldPhyRd  = Output(Vec(ISSUE_WIDTH, UInt(PHYS_REG_IDX_WIDTH.W)))

    val currentMapping = Output(Vec(ARCH_REG_NUM, UInt(PHYS_REG_IDX_WIDTH.W))) // 当前映射（可用于测试或调试）
  }
}


class FreeListIO extends Bundle {
  val in = new Bundle {
    val allocate = Input(Vec(ISSUE_WIDTH, Bool()))
    val dealloc  = Input(Vec(MAX_COMMIT_WB, ValidIO(UInt(PHYS_REG_IDX_WIDTH.W))))// 释放物理寄存器
    val rollbackTail = Input(ValidIO(UInt(log2Ceil(FREELIST_SIZE).W))) // 回滚目标指针
    val stall = Input(Bool()) // 阶段停顿
  }

  val out = new Bundle {
    val phyRd = Output(Vec(ISSUE_WIDTH, UInt(PHYS_REG_IDX_WIDTH.W)))
    val tailPtr = Output(UInt(log2Ceil(FREELIST_SIZE).W)) // 当前栈顶指针
  }
}

class RenameDispatchRegIO extends Bundle {
  val in = new Bundle {
    val renameVec = Input(new RenameBundle)
    val stall = Input(Bool())
    val flush = Input(Bool())
  }

  val out = new Bundle {
    val renameVec = Output(new RenameBundle)
  }
}
