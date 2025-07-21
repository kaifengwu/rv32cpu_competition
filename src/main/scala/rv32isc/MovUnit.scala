package rv32isc
import chisel3._
import chisel3.util._

import bundles._
import config.Configs._
import config.OoOParams._

// 单周期伪指令处理单元 - 处理寄存器间直接移动指令
// 不需要内部处理回滚，回滚由前级寄存器负责
class MovUnit extends Module {
  val io = IO(new MovUnitIO)

  // 默认输出初始化
  io.resultOut.valid := false.B
  io.resultOut.bits.valid := false.B
  io.resultOut.bits.phyDest := 0.U
  io.resultOut.bits.data := 0.U
  io.resultOut.bits.robIdx := 0.U

  // 默认不忙，可以接收指令
  io.issue.ready := io.resultOut.ready
  io.busy := !io.resultOut.ready && io.issue.valid

  // 如果有有效指令，单周期处理
  when(io.issue.valid) {
    // 获取源数据 - 从旁路总线或寄存器文件读取
    val pseudoData = Mux(io.bypassIn.map(bp => bp.valid && bp.phyDest === io.issue.bits.pseudoSrc).reduce(_ || _),
                        Mux1H(io.bypassIn.map(bp => bp.valid && bp.phyDest === io.issue.bits.pseudoSrc),
                              io.bypassIn.map(_.data)),
                        0.U) // 正常情况下应该从寄存器文件读取

    // 直接输出结果
    io.resultOut.valid := true.B
    io.resultOut.bits.valid := true.B
    io.resultOut.bits.phyDest := io.issue.bits.phyRd
    io.resultOut.bits.data := pseudoData
    io.resultOut.bits.robIdx := io.issue.bits.robIdx
  }
}

