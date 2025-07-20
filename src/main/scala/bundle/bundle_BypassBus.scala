package bundles

import chisel3._
import chisel3.util._
import config.OoOParams._
import config.Configs._
class BypassBusREG extends Bundle {
  val valid    = Bool()                           // 是否有效广播
  val phyDest  = UInt(PHYS_REG_IDX_WIDTH.W)       // 写回的物理寄存器号
  val robIdx   = UInt(ROB_IDX_WIDTH.W)            // 来源的 ROB 项目编号
}
class BypassBus extends Bundle {
  val reg     = new BypassBusREG                  // 广播的寄存器信息
  val data     = UInt(DATA_WIDTH.W)               // 写回的数据
}

class BypassUnitIO extends Bundle {
  val in = Input(Vec(NUM_BYPASS_PORTS, new BypassBus)) // 广播总线输入
  val out = Output(Vec(NUM_BYPASS_PORTS, new BypassBus)) // 广播总线输出
}
