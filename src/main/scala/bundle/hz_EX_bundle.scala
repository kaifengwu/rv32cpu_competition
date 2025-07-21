package bundles

import chisel3._
import chisel3.util._
import config.Configs._
import config.OoOParams._
import bundles._

// BU相关端口定义
class BU_IO extends Bundle {
  val rs1_data = Input(UInt(DATA_WIDTH.W))
  val rs2_data = Input(UInt(DATA_WIDTH.W))
  val pc       = Input(UInt(ADDR_WIDTH.W))
  val imm      = Input(UInt(DATA_WIDTH.W))
  val aluCtrl  = Input(new AluCtrlBundle) // 修改为AluCtrlBundle
  val predictedTaken = Input(Bool())
  val robIdx   = Input(UInt(ROB_IDX_WIDTH.W))
  val isReturn = Input(Bool()) // 是否是返回指令
}

class BU_OUT extends Bundle {
  val cmp        = Bool()
  val result     = UInt(ADDR_WIDTH.W) //result存的是jal/jalr的是pc+imm
  val branch_actual_target = UInt(ADDR_WIDTH.W) // 分支跳转的实际目标地址
  val outValid   = Bool()
  val isBranch   = Bool()
  val mispredict = Bool()
  val targetPredictionCorrect = Bool() // 预测的跳转地址是否正确
  val busy       = Bool()
  val pc         = UInt(ADDR_WIDTH.W) // 本条跳转指令的PC值（合并了branch_pc和jal_pc）
  val jal_pc4    = UInt(ADDR_WIDTH.W)
  val robIdx     = UInt(ROB_IDX_WIDTH.W) //ROB索引，传递给ROB，本阶段只是传递信号不做任何处理
  val isReturnOut   = Bool() // 是否是返回指令

  // 添加写回物理寄存器相关信息
  val phyRd = UInt(PHYS_REG_IDX_WIDTH.W)  // 目标物理寄存器编号（对于jal/jalr指令）
}

//ALU相关端口定义
class ALU_OUT extends Bundle {
    val result = UInt(DATA_WIDTH.W) // 运算结果
    val cmp    = Bool()             // 分支跳转比较结果（如 rs1 == rs2）
    val zero   = Bool()             // result == 0
    val outValid = Bool()           // 输出是否有效
    val busy = Bool()            // 是否忙碌,用于记分牌

    // 添加写回物理寄存器相关信息
    val phyRd = UInt(PHYS_REG_IDX_WIDTH.W)  // 目标物理寄存器编号
    val robIdx = UInt(ROB_IDX_WIDTH.W)      // 对应ROB项目编号
 }

class  ALU_IN extends Bundle {
  val rs1_data   = UInt(DATA_WIDTH.W)  // 源操作数1数据
  val rs2_data   = UInt(DATA_WIDTH.W)  // 源操作数2数据
  val imm        = UInt(DATA_WIDTH.W)  // 立即数
  val valid      = Bool()              // 输入是否有效
  val pc         = UInt(ADDR_WIDTH.W)  // 程序计数器
  val alu_ctrl   = new AluCtrlBundle   // ALU控制信号
  val robIdx     = UInt(ROB_IDX_WIDTH.W) // 乱序相关（可选，便于写回）
}

class ALUIO extends Bundle {
  val in = Input(new ALU_IN)
  val out = Output(new ALU_OUT) // ALU运算结果
  val out_ready = Input(Bool()) // 下游是否准备好接收数据
}

// 为新的解耦设计添加的接口
class ALUIO_Decoupled extends Bundle {
  val in = Flipped(Decoupled(new AluIssueEntry))  // 使用AluIssueEntry作为输入
  val out = Output(new ALU_OUT)                   // ALU运算结果
  val out_ready = Input(Bool())                   // 下游是否准备好接收数据
}

// 为BU添加的解耦设计接口
class BUIO_Decoupled extends Bundle {
  val in = Flipped(Decoupled(new BrIssueEntry))   // 使用BrIssueEntry作为输入
  val out = Output(new BU_OUT)                    // BU运算结果
  val out_ready = Input(Bool())                   // 下游是否准备好接收数据
}