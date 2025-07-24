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
  io.resultOut.reg.phyDest := resultPhyDest
  io.resultOut.data := resultData
  io.resultOut.reg.robIdx := resultRobIdx

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

// MovUnit的顶层模块，整合RS_MovU_Reg、MovUnit和MovU_Next_Reg(即MOV_WB_Reg)
class MovU_Top extends Module {
  val io = IO(new Bundle {
    // 从保留站接收指令
    val issue = Flipped(Decoupled(new LsuIssueEntry))

    // 写回接口
    val rob_wb = Output(ValidIO(new RobWritebackEntry))

    // 输出结果接口
    val resultOut = Output(new BypassBus)

    // 写回旁路总线
    val writebackBus = Output(new BypassBus)

    // 伪指令数据输入
    val storeEntry = Input(new StoreEntry)

    // 控制信号
    val stall = Input(Bool())
    val flush = Input(Bool())

    // 回滚信号
    val rollback = Input(Valid(new RsRollbackEntry))
  })

  // 实例化RS_MovU_Reg
  val rs_movU_reg = Module(new RS_MovU_Reg)
  rs_movU_reg.io.in <> io.issue
  rs_movU_reg.io.stall := io.stall
  rs_movU_reg.io.flush := io.flush
  rs_movU_reg.io.rollback := io.rollback

  // 实例化MovUnit
  val movUnit = Module(new MovUnit)
  movUnit.io.issue <> rs_movU_reg.io.out

  // 连接storeEntry
  movUnit.io.storeEntry := io.storeEntry

  // 实例化MovU_Next_Reg (即 MOV_WB_Reg)
  val movU_next_reg = Module(new MOV_WB_Reg)
  movU_next_reg.io.in <> movUnit.io.mov_out
  movU_next_reg.io.stall := io.stall
  movU_next_reg.io.flush := io.flush
  movU_next_reg.io.rollback := io.rollback

  // 连接写回接口
  io.rob_wb <> movU_next_reg.io.rob_wb

  // 从MovUnit输出结果总线 - 提供即时结果
  io.resultOut := movUnit.io.resultOut

  // 从MovU_Next_Reg输出WritebackBus信号
  io.writebackBus.valid := movU_next_reg.io.rob_wb.valid
  io.writebackBus.reg.phyDest := movU_next_reg.io.out.phyRd
  io.writebackBus.reg.robIdx := movU_next_reg.io.out.robIdx
  io.writebackBus.data := movU_next_reg.io.out.result
}
