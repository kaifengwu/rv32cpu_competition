package bundles

import chisel3._
import chisel3.util._
import config.OoOParams._
import config.Configs._
import config.InstructionConstants._

class RollbackSignal extends Bundle{
  val rollbackIdx = UInt(ROB_IDX_WIDTH.W)
  val robPc = UInt(ADDR_WIDTH.W)
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

        val wrongPredict = Bool()
        val redirectTarget = UInt(ADDR_WIDTH.W)
        val predictTarget = UInt(ADDR_WIDTH.W)
        val pc = UInt(ADDR_WIDTH.W)
        val tailPtr = UInt(log2Ceil(FREELIST_SIZE).W)
        val robIdx = UInt(ROB_IDX_WIDTH.W) // 分支指令在ROB中的索引
     }))
     val waitRet = Input(Bool())
     val robFull = Input(Bool())
     //flush用
     val rollBack = Input(ValidIO(new RollbackSignal))
     val predictedRet = Input(ValidIO(UInt(ADDR_WIDTH.W)))// RAS预测出的 ret 跳转目标（ValidIO）
     val tailRob = Input(UInt(ROB_IDX_WIDTH.W)) //ROB的栈顶

  }
  val out = new Bundle {
    val rollbackPc      = Output(ValidIO(UInt(ADDR_WIDTH.W))) // 分支失败时恢复状态
    val rollbackTail = Input(ValidIO(UInt(log2Ceil(FREELIST_SIZE).W))) // 回滚目标指针
    val rollBackIdx = Output(ValidIO(UInt(ROB_IDX_WIDTH.W))) // 回滚目标指令索引

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
 
    val retcommit = Output(ValidIO(Bool())) //BU返回给RAS
    val redirect = Output(ValidIO(UInt(ADDR_WIDTH.W))) // 来自EX分支重定向
    val retTarget = Output(ValidIO(UInt(ADDR_WIDTH.W))) // 来自EX分支重定向
    val update = Output(ValidIO(new PredictorUpdateBundle)) //分支预测器更新
    val rollback = Output(ValidIO(new RsRollbackEntry))

    val commit_to_Ras = Output(ValidIO(UInt(ADDR_WIDTH.W))) // 分支成功 commit 后移除快照
  }
}
