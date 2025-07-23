package bundles

import chisel3._
import chisel3.util._
import config.Configs._
import config.OoOParams._

class DispatchBundleIO extends Bundle{
  val in = new Bundle{
    val renameVec = Input(Vec(ISSUE_WIDTH, new RenameBundle)) // 重命名结果
    val stall_by_rs = Input(Bool())

    val reg = new Bundle {
      val readRS1Data  = Input(Vec(ISSUE_WIDTH, UInt(DATA_WIDTH.W)))
      val readRS1Ready = Input(Vec(ISSUE_WIDTH, Bool()))  // 改名为 Ready，表示数据是否准备好

      val readRS2Data  = Input(Vec(ISSUE_WIDTH, UInt(DATA_WIDTH.W)))
      val readRS2Ready = Input(Vec(ISSUE_WIDTH, Bool()))
    }
  }
  val out = new Bundle {
    val PRF_bunle = new Bundle {
      // === 新目标寄存器分配（来自 Rename）：需清除 valid 位 ===
      val alloc = Output(Vec(ISSUE_WIDTH, ValidIO(UInt(PHYS_REG_IDX_WIDTH.W))))
      // === 源寄存器读取请求 ===
      val readRS1 = Output(Vec(ISSUE_WIDTH, ValidIO(UInt(PHYS_REG_IDX_WIDTH.W))))
      val readRS2 = Output(Vec(ISSUE_WIDTH, ValidIO(UInt(PHYS_REG_IDX_WIDTH.W))))
    }
    val enqALU    = Output(Vec(ISSUE_WIDTH,ValidIO(new AluIssueEntry)))// 从 Dispatch 发射到 ALU RS
    val enqBR    = Output(Vec(ISSUE_WIDTH, ValidIO(new BrIssueEntry)))  // 多发射入队
    val enqLSU = Output(Vec(ISSUE_WIDTH, ValidIO(new LsuIssueEntry)))   // 多发射入队
    val allocate  = Output(Vec(ISSUE_WIDTH, ValidIO(new RobAllocateEntry)))
  }
}
