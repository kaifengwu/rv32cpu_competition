package bundles

import chisel3._
import chisel3.util._
import config.Configs._
import config.OoOParams._

class PRFBundle extends Bundle {
  val in = new Bundle {
    // === 写回端口（来自所有执行单元） ===
    val write = Vec(EXEC_UNITS, ValidIO(new Bundle {
      val addr = UInt(PHYS_REG_IDX_WIDTH.W)
      val data = UInt(DATA_WIDTH.W)
    }))

    // === 提交端口（来自 ROB）：标记这些物理寄存器可以释放 ===
    val commit = Vec(COMMIT_WIDTH, ValidIO(UInt(PHYS_REG_IDX_WIDTH.W)))

    // === 新目标寄存器分配（来自 Rename）：需清除 valid 位 ===
    val allocValid   = Vec(ISSUE_WIDTH, Bool())
    val allocPhysReg = Vec(ISSUE_WIDTH, UInt(PHYS_REG_IDX_WIDTH.W))

    // === 源寄存器读取请求 ===
    val readRS1 = Vec(ISSUE_WIDTH, UInt(PHYS_REG_IDX_WIDTH.W))
    val readRS2 = Vec(ISSUE_WIDTH, UInt(PHYS_REG_IDX_WIDTH.W))
  }

  val out = new Bundle {
    // === 源寄存器读取数据 ===
    val readRS1Data  = Vec(ISSUE_WIDTH, UInt(DATA_WIDTH.W))
    val readRS1Valid = Vec(ISSUE_WIDTH, Bool())

    val readRS2Data  = Vec(ISSUE_WIDTH, UInt(DATA_WIDTH.W))
    val readRS2Valid = Vec(ISSUE_WIDTH, Bool())
  }
}
