// storeQueue是一个暂存store指令的模块，一直到commit才能提交
package bundles

import chisel3._
import chisel3.util._
import config.Configs._
import config.OoOParams._

class StoreEntry extends Bundle {
  val addr   = UInt(ADDR_WIDTH.W)
  val data   = UInt(DATA_WIDTH.W) // masked 完毕
  val robIdx = UInt(ROB_IDX_WIDTH.W)
}

class StoreQueueBypassResult extends Bundle {
  val hit  = Bool()
  val data = UInt(DATA_WIDTH.W)
}

class StoreQueueIO extends Bundle {
  val in = new Bundle {
    val enq             = Flipped(Decoupled(new StoreEntry))           // 入队接口
    val rollback        = Input(Bool())
    val rollbackTarget  = Input(UInt(ROB_IDX_WIDTH.W))                 // 分支ROB编号
    val bypassAddr      = Input(Valid(UInt(ADDR_WIDTH.W)))            // 旁路地址（Valid 封装）
  }

  val out = new Bundle {
    val bypass          = Output(new StoreQueueBypassResult)          // Load 旁路结果
    val commitValid     = Output(Bool())                               // 是否有待提交的 store
    val commitEntry     = Output(new StoreEntry)                       // 提交用 entry
  }
}
