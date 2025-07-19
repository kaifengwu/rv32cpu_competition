// storeQueue是一个暂存store指令的模块，一直到commit才能提交
package bundles

import chisel3._
import chisel3.util._
import config.Configs._
import config.OoOParams._

class StoreQueueEnq extends Bundle {
  val addr   = UInt(ADDR_WIDTH.W)
  val data   = UInt(DATA_WIDTH.W)
  val mask   = UInt(4.W)              // 支持按字节掩码写入
  val robIdx = UInt(ROB_IDX_WIDTH.W) // 用于 commit/rollback 匹配
}

class StoreQueueCommit extends Bundle {
  val valid  = Bool()
  val robIdx = UInt(ROB_IDX_WIDTH.W)
}

class StoreQueueBypassQuery extends Bundle {
  val addr   = UInt(ADDR_WIDTH.W)
}

class StoreQueueBypassResult extends Bundle {
  val hit    = Bool()
  val data   = UInt(DATA_WIDTH.W)
  val mask   = UInt(4.W)
}

class StoreQueueIO extends Bundle {
  val in = new Bundle {
    val enq     = Flipped(Decoupled(new StoreQueueEnq))  // 执行完成后的 Store 入队
    val commit  = Input(new StoreQueueCommit)            // ROB 发出提交信号
    val rollback = Input(Bool())                         // 回滚信号：清除未提交项
    val rollbackTarget = Input(UInt(ROB_IDX_WIDTH.W))    // 回滚边界 ROB index
    val loadBypass = Input(new StoreQueueBypassQuery)    // Load 执行时查找是否命中
  }

  val out = new Bundle {
    val memWrite = Valid(new Bundle {                    // 最终提交写入内存
      val addr = UInt(ADDR_WIDTH.W)
      val data = UInt(DATA_WIDTH.W)
    })

    val bypass = Output(new StoreQueueBypassResult)      // load bypass 返回结果
  }
}
