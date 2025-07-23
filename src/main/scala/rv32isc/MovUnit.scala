package rv32isc
import chisel3._
import chisel3.util._

import bundles._
import config.Configs._
import config.OoOParams._
import config.LWB_InstructionConstants._

// 单周期伪指令处理单元 - 处理寄存器间直接移动指令，支持掩码计算
// 不需要内部处理回滚，回滚由前级寄存器负责
class MovUnit extends Module {
  val io = IO(new MovIO_Decoupled)

  // 定义线网 - 将在组合逻辑中计算出的结果
  val resultValid = WireDefault(false.B)
  val resultPhyDest = WireDefault(0.U(PHYS_REG_IDX_WIDTH.W))
  val resultData = WireDefault(0.U(DATA_WIDTH.W))
  val resultRobIdx = WireDefault(0.U(ROB_IDX_WIDTH.W))

  // 默认输出初始化
  io.resultOut.valid := resultValid
  io.resultOut.bits.reg.phyDest := resultPhyDest
  io.resultOut.bits.data := resultData
  io.resultOut.bits.reg.robIdx := resultRobIdx

  // 写回旁路总线
  io.writebackBus.valid := resultValid
  io.writebackBus.reg.phyDest := resultPhyDest
  io.writebackBus.reg.robIdx := resultRobIdx
  io.writebackBus.data := resultData

  // 默认不忙，可以接收指令
  io.issue.ready := true.B  // 总是可以接收指令
  io.busy := io.issue.valid

  // 如果有有效指令，单周期处理
  when(io.issue.valid) {
    // 获取源数据 - 从StoreEntry获取
    val pseudoData = io.storeEntry.data

    // 掩码计算逻辑 - 类似LSU中的掩码计算
    val maskedData = WireDefault(pseudoData)

    // 根据funct3函数码应用不同的掩码
    switch(io.issue.bits.func3) {
      // 字节操作 (LB/LBU/SB)
      is(F3_LB) {
        // 有符号扩展
        val byteData = pseudoData(7, 0)
        maskedData := Cat(Fill(24, byteData(7)), byteData)
      }
      is(F3_LBU) {
        // 无符号扩展
        val byteData = pseudoData(7, 0)
        maskedData := Cat(0.U(24.W), byteData)
      }
      // 半字操作 (LH/LHU/SH)
      is(F3_LH) {
        // 有符号扩展
        val halfwordData = pseudoData(15, 0)
        maskedData := Cat(Fill(16, halfwordData(15)), halfwordData)
      }
      is(F3_LHU) {
        // 无符号扩展
        val halfwordData = pseudoData(15, 0)
        maskedData := Cat(0.U(16.W), halfwordData)
      }
      // 字操作 (LW/SW) - 默认情况
      is(F3_LW) {
        maskedData := pseudoData
      }
    }

    // 将结果保存到线网中
    resultValid := true.B
    resultPhyDest := io.issue.bits.phyRd
    resultData := maskedData
    resultRobIdx := io.issue.bits.robIdx
  }

  // 添加MOV_OUT信号用于写回寄存器
  io.mov_out.valid := resultValid
  io.mov_out.bits.result := resultData
  io.mov_out.bits.phyRd := resultPhyDest
  io.mov_out.bits.robIdx := resultRobIdx
  io.mov_out.bits.busy := io.issue.valid
}
