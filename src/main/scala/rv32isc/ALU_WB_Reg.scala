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
    val rollback = Input(Valid(UInt(ROB_IDX_WIDTH.W))) // 回滚信号和回滚点
    val tail = Input(UInt(ROB_IDX_WIDTH.W))            // ROB尾指针
  })

  val reg = RegInit(0.U.asTypeOf(new ALU_OUT))
  val valid = RegInit(false.B)

  // 判断当前指令是否在回滚区间内 - 优化环形判断逻辑
  val inRollbackRange = WireDefault(false.B)
  when(io.rollback.valid && valid) {
    when(io.tail >= io.rollback.bits) {
      // 普通情况：[rollback, tail)
      inRollbackRange := reg.robIdx >= io.rollback.bits && reg.robIdx < io.tail
    }.otherwise {
      // 环形情况：tail < rollback
      inRollbackRange := (reg.robIdx >= io.rollback.bits) || (reg.robIdx < io.tail)
    }
  }

  // 生成内部flush信号 - 合并外部flush和回滚产生的flush
  val internal_flush = io.flush || (io.rollback.valid && inRollbackRange)

  // 寄存器更新逻辑
  when(internal_flush) {
    valid := false.B
    reg := 0.U.asTypeOf(new ALU_OUT)
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