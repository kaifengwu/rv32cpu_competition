package bundles

import chisel3._
import chisel3.util._
import config.OoOParams._
import config.Configs._
import config.InstructionConstants._

class RollbackSignal extends Bundle{
  val rollbackIdx = UInt(ROB_IDX_WIDTH.W)
  val robTail = UInt(ROB_IDX_WIDTH.W)
  val robPc = UInt(ADDR_WIDTH.W)
  val redirectPc = UInt(ADDR_WIDTH.W)
}

class ControlBundleIO extends Bundle{
  val in = new Bundle {
     val rs = new Bundle{
        val alu_rs_full = Input(Bool())
        val br_rs_full = Input(Bool())
        val lsu_rs_full = Input(Bool())
     }  
     val br = Input(ValidIO(new Bundle{
        val isBranch = Bool()
        val isJal = Bool()
        val isJalr = Bool()
        val isRet = Bool()
        val isJump = Bool()
     }))
     val waitRet = Input(Bool())
     val robFull = Input(Bool())
     //flush用
     val rollBack = Input(ValidIO(new RollbackSignal))
     val reture = Input(ValidIO(UInt(ADDR_WIDTH.W)))
  }
  val out = new Bundle {
    val redirect = Output(ValidIO(UInt(ADDR_WIDTH.W))) // 来自EX分支重定向
    val retTarget = Output(ValidIO(UInt(ADDR_WIDTH.W))) // 来自EX分支重定向
    val rollbackPc      = Output(ValidIO(UInt(ADDR_WIDTH.W))) // 分支失败时恢复状态

    val stall = new Bundle{
      val stall_IF = Output(Bool())
      val stall_ID = Output(Bool())
      val stall_RE = Output(Bool())
    }
    val flush = new Bundle{
      val flush_IF = Output(Bool())
      val flush_ID = Output(Bool())
      val flush_RE = Output(Bool())
    }

  }
}
