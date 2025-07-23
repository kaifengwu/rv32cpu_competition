package rv32isc
import chisel3._
import chisel3.util._
import bundles._
import config.Configs._
import config.OoOParams._
import config.InstructionConstants._
//BU的顶层模块叫BU_Top,实例化的时候实例化这个

// BU模块：处理分支、jal、jalr跳转逻辑，支持回滚和保留站接口
class BU extends Module {
  val io = IO(new BUIO_Decoupled)

  // 从BrIssueEntry提取操作数和控制信号
  val phyRd = io.in.bits.phyRd
  val robIdx = io.in.bits.robIdx
  val valid = io.in.valid
  val tailPtr = io.in.bits.tailPtr  // 提取回滚目标指针

  // 控制信号提取
  val isBranch = io.in.bits.isBranch
  val isJal = io.in.bits.isJal
  val isJalr = io.in.bits.isJalr
  val func3 = io.in.bits.func3

  // 数据提取
  val pc = io.in.bits.pc
  val imm = io.in.bits.imm
  val predictedTarget = io.in.bits.PredictTarget

  // 从保留站获取源操作数
  val src1 = WireDefault(0.U(DATA_WIDTH.W))
  val src2 = WireDefault(0.U(DATA_WIDTH.W))

  // 这里假设BrIssueEntry中的ready1和ready2表示寄存器已就绪
  // 实际实现中可能需要从PRF读取或使用前递

  val cmp = WireDefault(false.B)
  val jumpTarget = WireDefault(0.U(ADDR_WIDTH.W))
  val branch_actual_target = Wire(UInt(ADDR_WIDTH.W))
  val isReturn = WireDefault(false.B) // 假设通过某种方式判断返回指令
  val actualTaken = WireDefault(false.B)
  val jal_pc4 = WireDefault(0.U(ADDR_WIDTH.W))
  val predictedTaken = WireDefault(false.B) // 假设预测信息在BrIssueEntry中

  // 地址预测正确性判断信号
  val targetPredictionCorrect = WireDefault(false.B)

  // 确定分支类型并执行比较
  when(isBranch) {
    //使用func3字段，这是因为BrEntry传入的是func3而不是直接传入op类型。
    switch(func3) {
      is("b000".U) { cmp := src1 === src2 } // BEQ
      is("b001".U) { cmp := src1 =/= src2 } // BNE
      is("b100".U) { cmp := src1.asSInt < src2.asSInt } // BLT
      is("b101".U) { cmp := src1.asSInt >= src2.asSInt } // BGE
      is("b110".U) { cmp := src1 < src2 } // BLTU
      is("b111".U) { cmp := src1 >= src2 } // BGEU
    }

    actualTaken := cmp
    jumpTarget := pc + imm
    branch_actual_target := Mux(actualTaken, pc + imm, pc + 4.U)

    // B型指令：预测跳转地址与实际目标地址比较
    targetPredictionCorrect := predictedTarget === branch_actual_target
  }.elsewhen(isJal) {
    actualTaken := true.B
    jumpTarget := pc + imm
    jal_pc4 := pc + 4.U

    // J型指令：预测跳转地址与跳转目标地址比较
    targetPredictionCorrect := predictedTarget === jumpTarget
  }.elsewhen(isJalr) {
    actualTaken := true.B
    jumpTarget := (src1 + imm) & ~1.U // JALR地址计算规则
    jal_pc4 := pc + 4.U

    // JALR指令：预测跳转地址与跳转目标地址比较
    targetPredictionCorrect := predictedTarget === jumpTarget
  }

  val busy = valid // 输出有效时为busy
  val mispredict = actualTaken =/= predictedTaken

  // 设置输出信号 - 使用ValidIO接口
  io.out.valid := valid
  io.out.bits.cmp := cmp
  io.out.bits.result := jumpTarget
  io.out.bits.branch_actual_target := branch_actual_target
  io.out.bits.isBranch := isBranch
  io.out.bits.mispredict := mispredict
  io.out.bits.targetPredictionCorrect := targetPredictionCorrect
  io.out.bits.busy := busy
  io.out.bits.pc := pc // 统一使用原始指令PC
  io.out.bits.jal_pc4 := jal_pc4
  io.out.bits.robIdx := robIdx
  io.out.bits.isReturnOut := isReturn
  io.out.bits.phyRd := phyRd
  io.out.bits.tailPtr := tailPtr // 传递回滚目标指针

  // 旁路输出 - 在组合逻辑阶段直接输出
  io.bypassBus.valid := valid
  io.bypassBus.reg.phyDest := phyRd      // 写回的物理寄存器号
  io.bypassBus.reg.robIdx := robIdx      // 来源的ROB项目编号
  io.bypassBus.data := jumpTarget        // 对于分支和跳转指令，我们传递的是目标地址

  // 专门的写回旁路总线
  io.writebackBus.valid := valid
  io.writebackBus.reg.phyDest := phyRd
  io.writebackBus.reg.robIdx := robIdx
  io.writebackBus.data := jumpTarget

  // 处理输入就绪信号 - 当BU不忙时，可以接收新指令
  io.in.ready := !busy
}

// BU顶层模块，整合RS_br_Reg、BU和BU_WB_Reg
class BU_Top extends Module {
  val io = IO(new Bundle {
    // 从保留站接收指令
    val in = Flipped(Decoupled(new BrIssueEntry))

    // 写回接口
    val rob_wb = Output(ValidIO(new RobWritebackEntry))

    // 旁路和写回总线
    val bypassBus = Output(new BypassBus)        // 组合逻辑阶段产生的旁路信号
    val writebackBus = Output(new WritebackBus)  // 经过BU_WB_Reg后产生的写回信号
    val jumpBus = Output(ValidIO(new BU_OUT))    // 带valid的jump指令输出

    // 控制信号
    val stall = Input(Bool())
    val flush = Input(Bool())

    // 回滚信号
    val rollback = Input(Valid(new RsRollbackEntry))
  })

  // 实例化RS_br_Reg
  val rs_br_reg = Module(new RS_br_Reg)
  rs_br_reg.io.in <> io.in
  rs_br_reg.io.stall := io.stall
  rs_br_reg.io.flush := io.flush
  rs_br_reg.io.rollback := io.rollback

  // 实例化BU
  val bu = Module(new BU)
  bu.io.in <> rs_br_reg.io.out

  // 实例化BU_WB_Reg
  val bu_wb_reg = Module(new BU_WB_Reg)
  bu_wb_reg.io.in <> bu.io.out
  bu_wb_reg.io.stall := io.stall
  bu_wb_reg.io.flush := io.flush
  bu_wb_reg.io.rollback := io.rollback

  // 连接写回接口
   io.rob_wb <> bu_wb_reg.io.rob_wb

  // 在BU组合逻辑阶段输出Bypass旁路信号
  io.bypassBus := bu.io.bypassBus

  // 输出带valid信号的jump指令（来自BU_WB_Reg）
  io.jumpBus.valid := bu_wb_reg.io.rob_wb.valid
  io.jumpBus.bits := bu_wb_reg.io.out

  // 从BU_WB_Reg输出WritebackBus信号
  // 使用rob_wb的valid信号作为writebackBus的valid信号
  io.writebackBus.valid := bu_wb_reg.io.rob_wb.valid
  io.writebackBus.reg.phyDest := bu_wb_reg.io.out.phyRd
  io.writebackBus.reg.robIdx := bu_wb_reg.io.out.robIdx
  io.writebackBus.data := bu_wb_reg.io.out.result
}