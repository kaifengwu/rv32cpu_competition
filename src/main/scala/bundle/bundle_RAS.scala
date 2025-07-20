package bundles

import chisel3._
import chisel3.util._
import config.Configs._
import config.OoOParams._

class RASBundle extends Bundle {
  val in = new Bundle {
    // === 压栈请求 ===
    val pushValidVec  = Input(Vec(FETCH_WIDTH, Bool()))               // 哪些槽是 call
    val pushDataVec   = Input(Vec(FETCH_WIDTH, UInt(ADDR_WIDTH.W)))   // 对应返回地址

    // === 出栈请求 ===
    val popValid      = Input(Bool())                                 // 是否遇到 ret 指令

    // === 分支预测保存快照 ===
    val checkpoint    = Input(Vec(ISSUE_WIDTH, ValidIO(UInt(ADDR_WIDTH.W)))) // 分支发射时保存快照（以 PC 为 ID）

    // === 分支恢复（回滚） ===
    val rollback      = Input(Vec(ISSUE_WIDTH, ValidIO(UInt(ADDR_WIDTH.W)))) // 分支失败时恢复状态

    // === 分支提交（删除快照） ===
    val commit        = Input(Vec(ISSUE_WIDTH, ValidIO(UInt(ADDR_WIDTH.W)))) // 分支成功 commit 后移除快照

    val retcommit = Input(ValidIO(Bool()))
  }

  val out = new Bundle {
    val predictedRet = ValidIO(UInt(ADDR_WIDTH.W))                    // RAS预测出的 ret 跳转目标（ValidIO）
    val currentDepth = Output(UInt(log2Ceil(RAS_DEPTH).W))            // 当前栈深度
    val debugTop     = Output(UInt(ADDR_WIDTH.W))                     // 当前栈顶地址（调试用）
    val retstall   = Output(Bool())                                 // RAS 是否阻塞 ret（空栈 + 无 overflow）
  }
}
