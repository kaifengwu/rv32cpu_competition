package bundles

import chisel3._
import config.Configs._
import config.InstructionConstants._
import chisel3.experimental.BaseModule
// === 子控制字段 ===

class AluCtrlBundle extends Bundle {
  val aluOp        = UInt(4.W)
  val aluSrc       = Bool()   // 是否使用立即数
  val aluUnsigned  = Bool()   // 是否无符号比较
  val aluIsAuipc   = Bool()
  val aluIsLui     = Bool()
}

class WbCtrlBundle extends Bundle {
  val regWrite = Bool()
}

class BranchCtrlBundle extends Bundle {
  val isBranch = Bool()
  val isJalr   = Bool()
  val isJal   = Bool()
  val isJump   = Bool()
}

class RegBundle extends Bundle {
  val rs1 = UInt(REG_NUMS_LOG.W)
  val rs2 = UInt(REG_NUMS_LOG.W)
  val rd  = UInt(REG_NUMS_LOG.W)
}

class FuncBundle extends Bundle {
  val opcode = UInt(7.W)
  val funct3 = UInt(3.W)
  val funct7 = UInt(7.W)
}

class MemCtrlBundle extends Bundle {
  val memRead   = Bool()
  val memWrite  = Bool()
}

class DecodedBundle extends Bundle {
  val valid     = Bool()
  val pc        = UInt(ADDR_WIDTH.W)
  val inst      = UInt(INST_WIDTH.W)

  val reg       = new RegBundle
  val func      = new FuncBundle
  val imm       = UInt(DATA_WIDTH.W)
  val aluCtrl   = new AluCtrlBundle
  val memCtrl   = new MemCtrlBundle

  val isBranch  = Bool()
  val isJump    = Bool()
}

class IDStageIO extends Bundle {
  val in = new Bundle {
    val IFVec = Input(Vec(FETCH_WIDTH, new IFBundle))
    val stall = Input(Bool())
    val flush = Input(Bool())
  }

  val out = new Bundle {
    val decodedVec = Output(Vec(FETCH_WIDTH, new DecodedBundle))
  }
}

// Decoder 
class DecoderIO extends Bundle {
  val in = new Bundle {
    val insts  = Input(Vec(FETCH_WIDTH, UInt(INST_WIDTH.W)))
  }

  val out = new Bundle {
    val regs = Output(Vec(FETCH_WIDTH, new RegBundle))
    val func = Output(Vec(FETCH_WIDTH, new FuncBundle))
    val imm  = Output(Vec(FETCH_WIDTH,UInt(DATA_WIDTH.W)))
  }
}

class ControlUnitIO extends Bundle {
  val in = new Bundle {
    val opcode = Input(Vec(FETCH_WIDTH, UInt(7.W)))
    val funct3 = Input(Vec(FETCH_WIDTH, UInt(3.W)))
    val funct7 = Input(Vec(FETCH_WIDTH, UInt(7.W)))
  }

  val out = new Bundle {
    val aluCtrl = Output(Vec(FETCH_WIDTH, new AluCtrlBundle))
    val memCtrl = Output(Vec(FETCH_WIDTH, new MemCtrlBundle))
    val wbCtrl  = Output(Vec(FETCH_WIDTH, new WbCtrlBundle))
    val brCtrl  = Output(Vec(FETCH_WIDTH, new BranchCtrlBundle))
    val useRs1 = Output(Vec(FETCH_WIDTH, Bool()))
    val useRs2 = Output(Vec(FETCH_WIDTH, Bool()))
  }
}

class IDBundle extends  Bundle {
    val regs    = new RegBundle
    val func3    = UInt(3.W)
    val imm     = UInt(32.W)
    val ctrl    = new Bundle {
      val aluCtrl = new AluCtrlBundle
      val memCtrl = new MemCtrlBundle
      val wbCtrl  = new WbCtrlBundle
      val brCtrl  = new BranchCtrlBundle
    }
    val useRs1  = Bool()
    val useRs2  = Bool()
}

class IDIO extends Bundle {
  val in = new Bundle {
    val ifVec = Input(Vec(FETCH_WIDTH, new IFBundle))
    val stall = Input(Bool())
    val flush = Input(Bool())
  }
  val out = new Bundle{
    val idVec = Output(Vec(FETCH_WIDTH,new IDBundle))
    val isRet = Output(Vec(FETCH_WIDTH,Bool()))
  }
}
