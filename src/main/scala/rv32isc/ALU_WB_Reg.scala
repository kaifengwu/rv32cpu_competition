package rv32isc
import chisel3._
import chisel3.util._
import bundles._
import config.Configs._
import config.OoOParams._

class ALU_WB_Reg extends Module {
  val io = IO(new Bundle {
    val in = Flipped(ValidIO(new ALU_OUT))     // ALU阶段输出，使用ValidIO接口
    val out = Output(new ALU_OUT)              // WB阶段输入
    val rob_wb = Output(ValidIO(new RobWritebackEntry))  // 连接到ROB的写回接口
    val stall = Input(Bool())                  // 阻塞信号
    val flush = Input(Bool())                  // 外部冲刷信号

    // 回滚相关信号 - 确保同步清空
    val rollback = Input(Valid(new RsRollbackEntry)) // 回滚信号和回滚点
  })

  val reg = RegInit(0.U.asTypeOf(new ALU_OUT))
  val valid = RegInit(false.B)

  // 判断当前指令是否在回滚区间内 - 优化环形判断逻辑
  val inRollbackRange = WireDefault(false.B)
  when(io.rollback.valid && valid) {
    val rollbackIdx = io.rollback.bits.rollbackIdx
    val tailIdx = io.rollback.bits.tailIdx
    when(tailIdx >= rollbackIdx) {
      // 普通情况：[rollback, tail)
      inRollbackRange := reg.robIdx >= rollbackIdx && reg.robIdx < tailIdx
    }.otherwise {
      // 环形情况：tail < rollback
      inRollbackRange := (reg.robIdx >= rollbackIdx) || (reg.robIdx < tailIdx)
    }
  }

  // 生成内部flush信号 - 合并外部flush和回滚产生的flush
  val internal_flush = io.flush || (io.rollback.valid && inRollbackRange)

  // 寄存器更新逻辑
  when(internal_flush) {
    // 在目标区域时，冲刷信号优先，清空寄存器状态
    valid := false.B
    reg := 0.U.asTypeOf(new ALU_OUT)
  }.elsewhen(io.rollback.valid && !inRollbackRange) {
    // 不在目标区域但有回滚信号时，保存数据并stall一个周期
    when(io.in.valid) {
      valid := true.B
      reg := io.in.bits
    }
    // 不清除已有数据
  }.elsewhen(!io.stall) {
    valid := io.in.valid
    when(io.in.valid) {
      reg := io.in.bits
    }
  }

  // 输出连接
  io.out := reg

  // ROB写回接口连接
  io.rob_wb.valid := valid && !internal_flush
  io.rob_wb.bits.robIdx := reg.robIdx
}