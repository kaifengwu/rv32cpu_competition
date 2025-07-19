package bundles
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
  val isCSR      = Bool()
  val hasRd      = Bool()
  val rd         = UInt(PHYS_REG_IDX_WIDTH.W)

  // Store 相关字段
  val addr       = UInt(ADDR_WIDTH.W)     // 来自 EX 单元写回
  val addrValid  = Bool()                 // 是否写回了地址
}


class RobWritebackEntry extends Bundle {
  val robIdx     = UInt(ROB_IDX_WIDTH.W)
  val data       = UInt(DATA_WIDTH.W)
  val addr       = UInt(ADDR_WIDTH.W)     // 仅 store 有效
  val addrValid  = Bool()                 // 是否为有效地址写回
}

class RobCommitEntry extends Bundle {
  val robIdx = UInt(ROB_IDX_WIDTH.W)
  val rd     = UInt(PHYS_REG_IDX_WIDTH.W)
  val data   = UInt(DATA_WIDTH.W)
  val hasRd  = Bool()
  val isStore = Bool()
  val valid  = Bool() // 是否有效 commit
}

class RobFlushBundle extends Bundle {
  val valid = Bool()
  val target = UInt(ADDR_WIDTH.W)
}

class RobLoadBypassInfo extends Bundle {
  val valid      = Bool()
  val data       = UInt(DATA_WIDTH.W)
  val matched    = Bool()
  val phySrc     = UInt(PHYS_REG_IDX_WIDTH.W)
}

class LoadBypassReq extends Bundle {
  val robIdx = UInt(ROB_IDX_WIDTH.W)    // Load 自身的 ROB 编号
  val addr   = UInt(ADDR_WIDTH.W)       // 要访问的内存地址
}

class LoadBypassResp extends Bundle {
  val hit  = Bool()                     // 是否命中 ROB 中待提交的 store
  val data = UInt(DATA_WIDTH.W)        // 命中时返回的数据
}

// LSU 向 ROB 发出前馈查询请求
class LoadBypassIO extends Bundle {
  val req  = Flipped(Valid(new LoadBypassReq))   // 由 LSU 发出
  val resp = Valid(new LoadBypassResp)           // 由 ROB 响应
}

class ROBIO extends Bundle {
  val in = new Bundle {
    val allocate  = Flipped(Vec(ISSUE_WIDTH, Decoupled(new RobAllocateEntry)))
    val writeback = Input(Vec(EXEC_UNITS, Valid(new RobWritebackEntry)))
    val loadBypass = new LoadBypassIO // Load 前馈地址查询（由 LSU 请求）
  }

  val out = new Bundle {
    val commit = Vec(COMMIT_WIDTH, Valid(new RobCommitEntry))
    val flush  = Output(new RobFlushBundle)
    val storePending = Output(Bool())

    // Load 前递支持
    val storeForward = Output(new RobLoadBypassInfo)
  }
}

