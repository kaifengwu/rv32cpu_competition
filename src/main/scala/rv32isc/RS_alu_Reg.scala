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
  val rollback = Input(Valid(new RsRollbackEntry)) // 回滚信号和回滚点
}

// ALU保留站到ALU执行单元的单指令流水寄存器实现
class RS_alu_Reg extends Module {
  val io = IO(new RS_alu_RegIO)

  // 寄存器状态
  val valid = RegInit(false.B)
  val data = RegInit(0.U.asTypeOf(new AluIssueEntry))

  // 判断当前指令是否在回滚区间内 - 优化环形判断逻辑
  val inRollbackRange = WireDefault(false.B)
  when(io.rollback.valid && valid) {
    val robIdx = data.robIdx
    val rollbackIdx = io.rollback.bits.rollbackIdx
    val tailIdx = io.rollback.bits.tailIdx
    when(tailIdx >= rollbackIdx) {
      // 普通情况：[rollback, tail)
      inRollbackRange := robIdx >= rollbackIdx && robIdx < tailIdx
    }.otherwise {
      // 环形情况：tail < rollback
      inRollbackRange := (robIdx >= rollbackIdx) || (robIdx < tailIdx)
    }
  }

  // 生成内部flush信号 - 合并外部flush和回滚产生的flush
  val internal_flush = io.flush || (io.rollback.valid && inRollbackRange)

  // 处理输入
  when (internal_flush) {
    // 在目标区域时，冲刷信号优先，清空寄存器状态
    valid := false.B
    data := 0.U.asTypeOf(new AluIssueEntry)
  }.elsewhen (io.rollback.valid && !inRollbackRange) {
    // 不在目标区域但有回滚信号时，区分两种情况处理
    when (io.in.fire) {
      // 情况1：下一周期保留站发送新指令，stall一个周期（保存数据）
      valid := true.B
      data := io.in.bits
    }.otherwise {
      // 情况2：下一周期保留站不发送新指令，flush现有数据
      valid := false.B
      data := 0.U.asTypeOf(new AluIssueEntry)
    }
  }.elsewhen (!io.stall) {
    // 非阻塞状态下正常传输数据
    when (io.in.fire) {
      valid := true.B
      data := io.in.bits
    }.elsewhen (io.out.fire) {
      valid := false.B
    }
  }

  // 输入准备信号逻辑：修改为只要不需要回滚就置1
  // 按照要求，除非需要回滚，否则ready信号都置1
  io.in.ready := !io.rollback.valid

  // 输出有效信号和数据 - 确保回滚时不输出错误数据
  io.out.valid := valid && !internal_flush
  io.out.bits := data
}