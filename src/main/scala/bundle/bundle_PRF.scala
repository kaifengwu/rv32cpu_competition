package bundles

import chisel3._
import chisel3.util._
import config.Configs._
import config.OoOParams._

class PRFBundle extends Bundle {
  val in = new Bundle {
    // === 写回端口（来自所有执行单元） ===
    val write = Input(Vec(NUM_BYPASS_PORTS, new BypassBus)) // 前馈广播输入

    // === 新目标寄存器分配（来自 Rename）：需清除 valid 位 ===
    val alloc = Input(Vec(ISSUE_WIDTH, ValidIO(UInt(PHYS_REG_IDX_WIDTH.W))))

    // === 源寄存器读取请求 ===
    val readRS1 = Input(Vec(ISSUE_WIDTH, ValidIO(UInt(PHYS_REG_IDX_WIDTH.W))))
    val readRS2 = Input(Vec(ISSUE_WIDTH, ValidIO(UInt(PHYS_REG_IDX_WIDTH.W))))

    // === 提交阶段写回（来自 ROB） ===，调试用端口
    val commit_wb = Input(Vec(MAX_COMMIT_WB, ValidIO(UInt(PHYS_REG_IDX_WIDTH.W)))) // 提交阶段写回
  }
  val out = new Bundle {
    // === 源寄存器读取数据 ===
    val readRS1Data  = Output(Vec(ISSUE_WIDTH, UInt(DATA_WIDTH.W)))
    val readRS1Ready = Output(Vec(ISSUE_WIDTH, Bool()))  // 改名为 Ready，表示数据是否准备好

    val readRS2Data  = Output(Vec(ISSUE_WIDTH, UInt(DATA_WIDTH.W)))
    val readRS2Ready = Output(Vec(ISSUE_WIDTH, Bool()))

    val commit_out_data = Output(Vec(MAX_COMMIT_WB, ValidIO(UInt(DATA_WIDTH.W)))) // 提交阶段写回数据
  }
}
