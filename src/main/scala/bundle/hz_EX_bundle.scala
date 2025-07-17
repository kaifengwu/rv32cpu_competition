package bundles
import chisel3._
import config.Configs._
import config.InstructionConstants._

//BU相关端口定义
class BU_OUT extends Bundle {
  val cmp        = Bool()             // 跳转条件是否成立
  val result     = UInt(DATA_WIDTH.W) // 跳转目标地址
  val outValid   = Bool()             // 输出数据有效
  val isJump     = Bool()             // 是否为JAL/JALR
  val isJalr     = Bool()             // 是否为JALR
  val isBranch   = Bool()             // 是否为分支指令
  val mispredict = Bool()             // 预测是否错误
  val busy       = Bool()             // busy信号，用于记分牌s
}

class BU_IO extends Bundle {
  val in      = Input(new ALU_IN)         // 组合逻辑输入
  val predictedTaken  = Input(Bool())     // 分支预测
  val out     = Output(new BU_OUT)
  val ready = Input(Bool())             // 下游ready（Decoupled协议）
  val busy    = Output(Bool())            // busy信号
}

//ALU相关端口定义
class ALU_OUT extends Bundle {
    val result = UInt(DATA_WIDTH.W) // 运算结果
    val cmp    = Bool()             // 分支跳转比较结果（如 rs1 == rs2）
    val zero   = Bool()             // result == 0
    val outValid = Bool()           // 输出是否有效

    val busy = Bool()            // 是否忙碌,用于记分牌
 }

class  ALU_IN extends Bundle {
  val rs1_data   = UInt(DATA_WIDTH.W)  // 第一个操作数（rs1）
  val rs2_data   = UInt(DATA_WIDTH.W)  // 第二个操作数（rs2）
  val imm    = UInt(DATA_WIDTH.W)  // 立即数
  val valid  = Bool()              // 当前输入是否有效（flush/nop处理用）
  val pc = UInt(ADDR_WIDTH.W) // 程序计数器
  val alu_ctrl = new ControlUnitAlu // ALU控制信号
}

class ALUIO extends Bundle {
  val in = Input(new ALU_IN)
  val out = Output(new ALU_OUT) // ALU运算结果
  val out_ready = Input(Bool()) // 下游是否准备好接收数据
}