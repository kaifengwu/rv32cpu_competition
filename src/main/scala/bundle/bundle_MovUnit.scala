package bundles
import chisel3._
import chisel3.util._
import config.Configs._
import config.OoOParams._

// MovUnit模块的输入输出接口
// 移除回滚信号，由前级寄存器处理回滚
class MovUnitIO extends Bundle {
  // 从保留站接收指令
  val issue = Flipped(Decoupled(new MovIssueEntry))

  // 旁路总线接口
  val bypassIn = Input(Vec(NUM_BYPASS_PORTS, new BypassBus))   // 接收前馈数据

  // 结果输出接口
  val resultOut = ValidIO(new BypassBus)                       // 最终结果输出改为ValidIO

  // 写回旁路接口
  val writebackBus = Output(new WritebackBus)                  // 添加专门的写回旁路总线

  // 忙信号
  val busy = Output(Bool())
}

// 伪指令发射项
class MovIssueEntry extends Bundle {
  val robIdx = UInt(ROB_IDX_WIDTH.W)
  val pc = UInt(ADDR_WIDTH.W)

  val phyRd = UInt(PHYS_REG_IDX_WIDTH.W)       // 目标物理寄存器
  val pseudoSrc = UInt(PHYS_REG_IDX_WIDTH.W)   // 伪指令来源寄存器

  val funct3 = UInt(3.W)                       // 函数码，用于掩码计算
  val valid = Bool()
}


