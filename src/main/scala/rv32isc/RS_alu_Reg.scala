package rv32isc

import chisel3._
import chisel3.util._
import config.OoOParams._
import config.Configs._
import bundles._

// ALU保留站到ALU执行单元的单指令流水寄存器接口
class RS_alu_RegIO extends Bundle {
  val in = Flipped(Decoupled(new AluIssueEntry))  // 从ALU保留站接收单条指令
  val out = Decoupled(new AluIssueEntry)          // 发送到ALU执行单元
  val stall = Input(Bool())                       // 流水线阻塞信号
  val flush = Input(Bool())                       // 流水线冲刷信号

  // 回滚相关信号
  val rollback = Input(Valid(UInt(ROB_IDX_WIDTH.W))) // 回滚信号和回滚点
  val tail = Input(UInt(ROB_IDX_WIDTH.W))            // ROB尾指针
}

class RS_alu_Reg extends Module {
  val io = IO(new RS_alu_RegIO)

  // 寄存器状态
  val valid = RegInit(false.B)
  val data = RegInit(0.U.asTypeOf(new AluIssueEntry))

  // 判断是否在回滚区间内（优化环形判断）
  val inRollbackRange = WireDefault(false.B)
  when(io.rollback.valid && valid) {
    val robIdx = data.robIdx
    when(io.tail >= io.rollback.bits) {
      inRollbackRange := robIdx >= io.rollback.bits && robIdx < io.tail
    }.otherwise {
      inRollbackRange := (robIdx >= io.rollback.bits) || (robIdx < io.tail)
    }
  }

  // 内部信号生成
  val internal_flush = io.flush || (io.rollback.valid && inRollbackRange)
  val internal_stall = io.stall || (io.rollback.valid && !inRollbackRange)

  // 寄存器更新逻辑（优先级：flush > stall > 正常更新）
  when (internal_flush) {
    // 情况1：冲刷（回滚区域内或外部flush）
    valid := false.B
    data := 0.U.asTypeOf(new AluIssueEntry)
  }.elsewhen (internal_stall) {
    // 情况2：阻塞（回滚区域外或外部stall）
    // 保持寄存器值不变，无需额外操作
    valid := false.B
    data := io.in.bits // 保持当前值
  }.otherwise {
    // 情况3：正常更新
    when (io.in.fire) {
      valid := true.B
      data := io.in.bits
    }.otherwise {
      valid := false.B
      data := 0.U.asTypeOf(new AluIssueEntry)
    }
  }

  // 输入准备逻辑
  io.in.ready := !internal_stall // 阻塞时拒绝新输入

  // 输出控制（冲刷时输出无效）
  io.out.valid := valid && !internal_flush
  io.out.bits := data
}