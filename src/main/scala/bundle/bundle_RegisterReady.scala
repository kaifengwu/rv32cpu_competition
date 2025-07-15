package bundles 

import chisel3._
import chisel3.util._

import bundles._
import config.Configs._;
import config.OoOParams._

class RegisterReadyTableIO extends Bundle {
  // 查询接口（供保留栈或调度单元查是否ready）
  val query = Input(Vec(ISSUE_WIDTH, UInt(PHYS_REG_IDX_WIDTH.W)))
  val ready = Output(Vec(ISSUE_WIDTH, Bool()))

  // 写回广播接口（标记这些寄存器为 ready）
  val setReadyValid = Input(Vec(NUM_BYPASS_PORTS, Bool()))
  val setReadyReg   = Input(Vec(NUM_BYPASS_PORTS, UInt(PHYS_REG_IDX_WIDTH.W)))

  // 重命名阶段分配新寄存器后设置为 not ready
  val setBusyValid = Input(Vec(ISSUE_WIDTH, Bool()))
  val setBusyReg   = Input(Vec(ISSUE_WIDTH, UInt(PHYS_REG_IDX_WIDTH.W)))
}
