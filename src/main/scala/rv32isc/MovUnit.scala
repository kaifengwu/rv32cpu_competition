package rv32isc
import chisel3._
import chisel3.util._

import bundles._
import config.Configs._
import config.OoOParams._


// LSU保留站到MovUnit的转换适配器
// 负责将LsuIssueEntry转换为MovIssueEntry，包括传递funct3字段用于掩码计算
class LsuToMovAdapter extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new LsuIssueEntry))   // 从LSU保留站接收
    val out = Decoupled(new MovIssueEntry)           // 输出到MovUnit
  })

  // 将输入有效性直接传递给输出
  io.out.valid := io.in.valid

  // 准备好信号反向传递
  io.in.ready := io.out.ready

  // 转换LsuIssueEntry到MovIssueEntry
  io.out.bits.robIdx := io.in.bits.robIdx
  io.out.bits.pc := io.in.bits.pc
  io.out.bits.phyRd := io.in.bits.phyRd
  io.out.bits.pseudoSrc := io.in.bits.pseudoSrc
  io.out.bits.funct3 := io.in.bits.func3            // 将func3传递给funct3用于掩码计算
  io.out.bits.valid := io.in.bits.valid
}

// 单周期伪指令处理单元 - 处理寄存器间直接移动指令，支持掩码计算
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

    // 掩码计算逻辑 - 类似LSU中的掩码计算
    val maskedData = WireDefault(pseudoData)

    // 根据funct3函数码应用不同的掩码
    switch(io.issue.bits.funct3) {
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

    // 直接输出掩码处理后的结果
    io.resultOut.valid := true.B
    io.resultOut.bits.valid := true.B
    io.resultOut.bits.phyDest := io.issue.bits.phyRd
    io.resultOut.bits.data := maskedData
    io.resultOut.bits.robIdx := io.issue.bits.robIdx
  }
}

