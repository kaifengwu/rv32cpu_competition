package bundles

import chisel3._
import chisel3.util._
import config.Configs._
import config.OoOParams._


class RobAllocateEntry extends Bundle {
  val robIdx     = UInt(ROB_IDX_WIDTH.W)
  val isStore    = Bool()
  val isLoad     = Bool()
  val isBranch   = Bool()
  val hasRd      = Bool()
  val pc = UInt(ADDR_WIDTH.W)

  val lrd = UInt(REG_NUMS.W) // 建议增加逻辑 rd（用于提交阶段判断 WAW）
  val rd         = UInt(PHYS_REG_IDX_WIDTH.W)

  // Store 相关字段
//    val addr       = UInt(ADDR_WIDTH.W)     // 来自 EX 单元写回
//    val addrValid  = Bool()                 // 是否写回了地址
}


class RobWritebackEntry extends Bundle {
  val robIdx     = UInt(ROB_IDX_WIDTH.W)
}

class RobCommitWbEntry extends Bundle {
  val robIdx = UInt(ROB_IDX_WIDTH.W)
  val rd     = UInt(PHYS_REG_IDX_WIDTH.W)
  val hasRd  = Bool()
  val isStore = Bool()
  val pc = UInt(ADDR_WIDTH.W)
  val lrd = UInt(REG_NUMS.W) // 建议增加逻辑 rd（用于提交阶段判断 WAW）
}

class RobCommitStoreEntry extends Bundle {
  val robIdx = UInt(ROB_IDX_WIDTH.W)
  val pc = UInt(ADDR_WIDTH.W)
  val isStore = Bool()
}

class RobFlushBundle extends Bundle {
  val valid = Bool()
  val target = UInt(ADDR_WIDTH.W)
}

class ROBIO extends Bundle {
  val in = new Bundle {
    val allocate  = Input(Vec(ISSUE_WIDTH, ValidIO(new RobAllocateEntry)))
    val writeback = Input(Vec(EXEC_UNITS, ValidIO(new RobWritebackEntry)))
    // 使用 ValidIO 封装回滚请求
    val rollback = Input(ValidIO(UInt(ROB_IDX_WIDTH.W))) // .valid + .bits
  }

  val out = new Bundle {
    val commit_wb = Vec(MAX_COMMIT_WB, ValidIO(new RobCommitWbEntry))
    val commit_store = Vec(MAX_COMMIT_STORE, ValidIO(new RobCommitStoreEntry))
    val commitCount = Output(ValidIO(UInt(ROB_IDX_WIDTH.W)))
    val tail = UInt(ROB_IDX_WIDTH.W)
    // flush 被移除
  }
}

// ROB分配器端口，在dispatch生成
class RobIndexAllocatorIO extends Bundle {
  val in = new Bundle {
    val allocateValid = Input(Vec(ISSUE_WIDTH, Bool()))
    val commitCount = Input(ValidIO(UInt(ROB_IDX_WIDTH.W)))
    val rollback      = Input(ValidIO(UInt(ROB_IDX_WIDTH.W)))
    val stall = Input(Bool()) // 阶段停顿
  }
  val out = new Bundle {
    val allocateIdx = Output(Vec(ISSUE_WIDTH, UInt(ROB_IDX_WIDTH.W))) // 分配编号
    val isFull   = Output(Bool()) // ROB满
  }
}
