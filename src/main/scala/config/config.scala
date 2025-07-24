package config

import chisel3._
import math._

object InstructionConstants {
  // ------------------------------
  // RISC-V 指令类型 opcode 常量
  // ------------------------------
  val OP_R      = "b0110011".U(7.W)
  val OP_I      = "b0010011".U(7.W)
  val OP_LOAD   = "b0000011".U(7.W)
  val OP_STORE  = "b0100011".U(7.W)
  val OP_BRANCH = "b1100011".U(7.W)
  val OP_JAL    = "b1101111".U(7.W)
  val OP_JALR   = "b1100111".U(7.W)
  val OP_LUI    = "b0110111".U(7.W)
  val OP_AUIPC  = "b0010111".U(7.W)

  // ------------------------------
  // funct3 常量
  // ------------------------------
  val F3_ADD_SUB = "b000".U(3.W)
  val F3_SLL     = "b001".U(3.W)
  val F3_SLT     = "b010".U(3.W)
  val F3_SLTU    = "b011".U(3.W)
  val F3_XOR     = "b100".U(3.W)
  val F3_SR      = "b101".U(3.W)
  val F3_OR      = "b110".U(3.W)
  val F3_AND     = "b111".U(3.W)

  val F3_BEQ     = "b000".U(3.W)
  val F3_BNE     = "b001".U(3.W)
  val F3_BLT     = "b100".U(3.W)
  val F3_BGE     = "b101".U(3.W)
  val F3_BLTU    = "b110".U(3.W)
  val F3_BGEU    = "b111".U(3.W)

  // ------------------------------
  // funct7 常量
  // ------------------------------
  val F7_ADD     = "b0000000".U(7.W)
  val F7_SUB     = "b0100000".U(7.W)
  val F7_SRL     = "b0000000".U(7.W)
  val F7_SRA     = "b0100000".U(7.W)

  // ------------------------------
  // ALU 操作类型编码
  // ------------------------------
  val OP_TYPES_WIDTH = 4
  val OP_NOP = "b0000".U
  val OP_ADD = "b0001".U
  val OP_SUB = "b0010".U
  val OP_AND = "b0100".U
  val OP_OR  = "b0101".U
  val OP_XOR = "b0111".U
  val OP_SLL = "b1000".U
  val OP_SRL = "b1001".U
  val OP_SRA = "b1011".U
  val OP_EQ  = "b1100".U
  val OP_NEQ = "b1101".U
  val OP_LT  = "b1110".U
  val OP_GE  = "b1111".U

  // ------------------------------
  // CSR 指令相关常量
  // ------------------------------
  val OP_SYSTEM = "b1110011".U(7.W)

  val F3_CSRRW  = "b001".U(3.W)
  val F3_CSRRS  = "b010".U(3.W)
  val F3_CSRRC  = "b011".U(3.W)
  val F3_CSRRWI = "b101".U(3.W)
  val F3_CSRRSI = "b110".U(3.W)
  val F3_CSRRCI = "b111".U(3.W)

  val CSR_MCYCLE = "hC00".U(12.W)
  val CSR_MTIME  = "hC01".U(12.W)
  val CSR_MSTATUS = "h300".U(12.W)
  val CSR_MEPC    = "h341".U(12.W)
  val CSR_MCAUSE  = "h342".U(12.W)
  val CSR_MTVEC   = "h305".U(12.W)

  val IMM_CSR = "b101".U(3.W)

  // ------------------------------
  // 立即数类型
  // ------------------------------
  val IMM_I = "b000".U(3.W)
  val IMM_S = "b001".U(3.W)
  val IMM_B = "b010".U(3.W)
  val IMM_J = "b011".U(3.W)
  val IMM_U = "b100".U(3.W)
}

// ------------------------------
// RV32 核心常量参数
// ------------------------------
object Configs {
  val FETCH_WIDTH = 2
  val ADDR_WIDTH = 32
  val ADDR_BYTE_WIDTH = ADDR_WIDTH / 8
  val DATA_WIDTH = 32
  val DATA_WIDTH_H = 16
  val DATA_WIDTH_B = 8

  val INST_WIDTH = 32
  val INST_BYTE_WIDTH = INST_WIDTH / 8
  val INST_BYTE_WIDTH_LOG = ceil(log(INST_BYTE_WIDTH) / log(2)).toInt
  val MEM_INST_SIZE = 16384 * 16

  val DATA_BYTE_WIDTH = DATA_WIDTH / 8
  val DATA_BYTE_WIDTH_LOG = ceil(log(DATA_BYTE_WIDTH) / log(2)).toInt
  val MEM_DATA_SIZE = 1024 * 1024

  val START_ADDR = 0x00000000

  val REG_NUMS = 32
  val REG_NUMS_LOG = ceil(log(REG_NUMS) / log(2)).toInt

  val BHT_INDEX_WIDTH = 8
  val TAG_WIDTH = 12
  val BHT_SIZE = 1 << BHT_INDEX_WIDTH
  val PREDICT_STACK_DEPTH = 4

  val CSR_NUMS = 4096
  val CSR_NUM_LOG = ceil(log(CSR_NUMS) / log(2)).toInt

  val path = new java.io.File("src/test/build/dhrystone/inst_hex.hex").getAbsolutePath
}

// ------------------------------
// Load/Store 指令 funct3
// ------------------------------
object LWB_InstructionConstants {
  val F3_LB  = "b000".U(3.W)
  val F3_LH  = "b001".U(3.W)
  val F3_LW  = "b010".U(3.W)
  val F3_LBU = "b100".U(3.W)
  val F3_LHU = "b101".U(3.W)

  val F3_SB  = "b000".U(3.W)
  val F3_SH  = "b001".U(3.W)
  val F3_SW  = "b010".U(3.W)
}

// ------------------------------
// 乱序执行参数
// ------------------------------

object OoOParams {
  val ISSUE_WIDTH  = 2


  val PHYS_REG_NUM = 128 
  val PHYS_REG_IDX_WIDTH = ceil(log(PHYS_REG_NUM) / log(2)).toInt

  val ROB_SIZE = 32 
  val ROB_IDX_WIDTH = ceil(log(ROB_SIZE) / log(2)).toInt

  val RS_ALU_SIZE = 16 
  val RS_BR_SIZE  = 8 
  val RS_LS_SIZE  = 8

  val ALU_UNITS = 2
  val BR_UNITS  = 1
  val LSU_UNITS = 1
  val MOV_UNITS = 1

  val FREELIST_SIZE = PHYS_REG_NUM - Configs.REG_NUMS
  val ARCH_REG_NUM = Configs.REG_NUMS
  val ARCH_REG_IDX_WIDTH = Configs.REG_NUMS_LOG
  val NUM_BYPASS_PORTS = ALU_UNITS + LSU_UNITS + MOV_UNITS + BR_UNITS
  val EXEC_UNITS = ALU_UNITS + BR_UNITS + LSU_UNITS + MOV_UNITS

  val MAX_COMMIT_WIDTH = ALU_UNITS + BR_UNITS + LSU_UNITS + MOV_UNITS // 最大提交宽度

  //RAS参数
  val RAS_DEPTH = 32
  val MAX_RAS_CHECKPOINTS = 16

}

// ------------------------------
// 执行状态标记（2 bits）
// ------------------------------
object ExecStatus {
  val INVALID = "b00".U(2.W)
  val WAITING = "b01".U(2.W)
  val READY   = "b10".U(2.W)
  val DONE    = "b11".U(2.W)
}

// ------------------------------
// 指令类型标记（3 bits）
// ------------------------------
object InstType {
  val NOP   = "b000".U(3.W)
  val ALU   = "b001".U(3.W)
  val BR    = "b010".U(3.W)
  val LOAD  = "b011".U(3.W)
  val STORE = "b100".U(3.W)
  val JUMP  = "b101".U(3.W)
  val CSR   = "b110".U(3.W)
}

