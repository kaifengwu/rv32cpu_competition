package bundles

import chisel3._
import chisel3.util._
import config.Configs._
import config.OoOParams._

class LsuIssueEntry extends Bundle {
  val robIdx = UInt(ROB_IDX_WIDTH.W)
  val pc = UInt(ADDR_WIDTH.W)

  val isLoad = Bool()
  val isStore = Bool()

  val phyRd = UInt(PHYS_REG_IDX_WIDTH.W)          // Load 写回目标寄存器
  val phyAddrBase = UInt(PHYS_REG_IDX_WIDTH.W)     // 地址基址寄存器
  val addrReady = Bool()                           // 地址是否准备好

  val dataOrPseudoSrc = UInt(PHYS_REG_IDX_WIDTH.W)    // Store写入数据寄存器或伪指令来源，合并了store和mov伪指令
  val dataReady = Bool()                           // 数据是否就绪

  val func3 = UInt(3.W)

  val imm = UInt(DATA_WIDTH.W)
  val memCtrl = new MemCtrlBundle

  val valid = Bool()

  // 若是伪指令，则以下字段有效
  val isPseudoMov = Bool()                         // 是否为伪指令
}

class LsuRSIO extends Bundle {
  val in = new Bundle {
    val enq = Flipped(Vec(ISSUE_WIDTH, Decoupled(new LsuIssueEntry)))   // 多发射入队
    val bypass = Input(Vec(NUM_BYPASS_PORTS, new BypassBus))            // 前馈广播输入
  }

  val out = new Bundle {
    val issue = Vec(LSU_UNITS, Decoupled(new LsuIssueEntry))            // 顺序发射出口（正常 LSU）
    val pseudo = Vec(MOV_UNITS, Decoupled(new LsuIssueEntry))           // MOV 发射口
    val full = Output(Bool())                                           // 保留栈满
  }
}

