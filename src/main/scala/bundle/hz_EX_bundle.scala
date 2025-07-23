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

  // 添加回滚目标指针
  val tailPtr = UInt(log2Ceil(FREELIST_SIZE).W)  // 回滚目标指针，用于预测错误时恢复
}

//ALU相关端口定义
class ALU_OUT extends Bundle {
    val result = UInt(DATA_WIDTH.W) // 运算结果
    val cmp    = Bool()             // 分支跳转比较结果（如 rs1 == rs2）
    val zero   = Bool()             // result == 0
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
  val out = ValidIO(new ALU_OUT) // ALU运算结果改为ValidIO
  val bypassBus = Output(new ALU_OUT) // 添加Bypassbus旁路信号
}

// 为新的解耦设计添加的接口
class ALUIO_Decoupled extends Bundle {
  val in = Flipped(Decoupled(new AluIssueEntry))  // 使用AluIssueEntry作为输入
  val out = ValidIO(new ALU_OUT)                  // ALU运算结果改为ValidIO
  val bypassBus = Output(new BypassBus)             // 添加Bypassbus旁路信号
  val writebackBus = Output(new WritebackBus)     // 添加专门的写回旁路总线
}

// 为BU添加的解耦设计接口
class BUIO_Decoupled extends Bundle {
  val in = Flipped(Decoupled(new BrIssueEntry))   // 使用BrIssueEntry作为输入
  val out = ValidIO(new BU_OUT)                   // BU运算结果改为ValidIO
  val bypassBus = Output(new BypassBus)           // 添加Bypassbus旁路信号，使用正确的BypassBus类型
  val writebackBus = Output(new WritebackBus)     // 添加专门的写回旁路总线
}

class WritebackBusREG extends Bundle {
  val phyDest  = UInt(PHYS_REG_IDX_WIDTH.W)       // 写回的物理寄存器号
  val robIdx   = UInt(ROB_IDX_WIDTH.W)            // 来源的 ROB 项目编号
}

class WritebackBus extends Bundle {
  val valid    = Bool()                           // 是否有效写回
  val reg     = new WritebackBusREG               // 写回的寄存器信息
  val data     = UInt(DATA_WIDTH.W)               // 写回的数据
}

class WritebackUnitIO extends Bundle {
  val in = Input(Vec(NUM_BYPASS_PORTS, new WritebackBus)) // 写回总线输入
  val out = Output(Vec(NUM_BYPASS_PORTS, new WritebackBus)) // 写回总线输出
}

// MovUnit的输出结构
class MOV_OUT extends Bundle {
  val result = UInt(DATA_WIDTH.W)   // 移动/掩码处理后的结果
  val busy = Bool()                 // 是否忙碌,用于记分牌

  // 添加写回物理寄存器相关信息
  val phyRd = UInt(PHYS_REG_IDX_WIDTH.W)  // 目标物理寄存器编号
  val robIdx = UInt(ROB_IDX_WIDTH.W)      // 对应ROB项目编号
}

// 为MovUnit添加的解耦设计接口
class MovIO_Decoupled extends Bundle {
  val issue = Flipped(Decoupled(new LsuIssueEntry))   // 使用LsuIssueEntry作为输入
  val storeEntry = Input(new StoreEntry)              // 输入StoreEntry
  val resultOut = ValidIO(new BypassBus)              // MovUnit运算结果
  val writebackBus = Output(new WritebackBus)         // 添加专门的写回旁路总线
  val busy = Output(Bool())                           // 忙信号
  val mov_out = ValidIO(new MOV_OUT)                  // 添加MOV_OUT输出接口供写回寄存器使用
}
