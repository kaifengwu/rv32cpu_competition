package bundles

import chisel3._
import chisel3.util._
import config.Configs._
import config.InstructionConstants._

class PCRegIO extends Bundle {
  val in = new Bundle {
    val stall    = Input(Bool())
    val predict = Input(ValidIO(UInt(ADDR_WIDTH.W))) // 来自分支预测器
    val redirect = Input(ValidIO(UInt(ADDR_WIDTH.W))) // 来自EX分支重定向
  }

  val out = new Bundle {
    val pcVec  = Output(Vec(FETCH_WIDTH, UInt(ADDR_WIDTH.W))) // fetch 地址
    val pcBase = Output(UInt(ADDR_WIDTH.W))                   // 当前 base PC
  }
}

class BranchPredictorIO extends Bundle {
  val in = new Bundle {
    val pcVec = Input(Vec(FETCH_WIDTH, UInt(ADDR_WIDTH.W)))
    val update = Input(ValidIO(new PredictorUpdateBundle))
  }

  val out = new Bundle {
    val redirect = Output(ValidIO(UInt(ADDR_WIDTH.W)))
     //bit mask 表示哪些 slot 应被 flush
    val maskAfterRedirect = Output(UInt(FETCH_WIDTH.W)) 
  }
}

class IFStageIO extends Bundle {
  val in = new Bundle {
    val stall  = Input(Bool())
    val flush  = Input(Bool())
    val update = Input(ValidIO(new PredictorUpdateBundle))
    val redirect = Input(ValidIO(UInt(ADDR_WIDTH.W)))
  }

  val out = new Bundle {
    val toDecode = Output(Vec(FETCH_WIDTH, new IFBundle))
  }
}

class IFBundle extends Bundle {
  val pc         = UInt(ADDR_WIDTH.W)
  val inst       = UInt(INST_WIDTH.W)
  val isJump   = Bool() // 是否跳转
  val jumpTarget = UInt(ADDR_WIDTH.W)
}

class PredictorUpdateBundle extends Bundle {
  val pc      = UInt(ADDR_WIDTH.W)
  val taken   = Bool()//预测为跳转
  val target  = UInt(ADDR_WIDTH.W)
}

