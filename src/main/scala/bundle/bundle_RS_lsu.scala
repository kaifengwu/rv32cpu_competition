package bundles

import chisel3._
import chisel3.util._
import config.Configs._
import config.OoOParams._

//Dispatch输入用
class LsuIssueEntry extends Bundle {
  val robIdx = UInt(ROB_IDX_WIDTH.W)
  val pc = UInt(ADDR_WIDTH.W)

  val isLoad = Bool()
  val isStore = Bool()

  val phyRd = UInt(PHYS_REG_IDX_WIDTH.W)          // Load 写回目标寄存器
  val phyAddrBaseDest = UInt(PHYS_REG_IDX_WIDTH.W)     // 地址基址寄存器
  val AddrBaseData = UInt(DATA_WIDTH.W)
  val addrReady = Bool()                           // 地址是否准备好

  val phyStoreDataDest = UInt(PHYS_REG_IDX_WIDTH.W)    // Store 写入数据寄存器
  val StoreData = UInt(DATA_WIDTH.W)
  val dataReady = Bool()                           // 写入数据是否就绪

  val func3 = UInt(3.W)

  val imm = UInt(DATA_WIDTH.W)
  val memCtrl = new MemCtrlBundle

   // 若是伪指令，则以下字段有效
  val isMov = Bool()                         // 是否为伪指令;
}
 


class LsuRSIO extends Bundle {
  val in = new Bundle {
    val enq = Flipped(Vec(ISSUE_WIDTH, Decoupled(new LsuIssueEntry)))   // 多发射入队
    val bypass = Input(Vec(NUM_BYPASS_PORTS, new BypassBus))            // 前馈广播输入
    val rollback = Input(ValidIO(new RsRollbackEntry))
    val lsuReady = Input(Vec(ALU_UNITS, Bool())) // 来自每个 LSU 执行单元的 ready 信号
  }

  val out = new Bundle {
    val issue = Vec(LSU_UNITS, Decoupled(new LsuIssueEntry))            // 顺序发射出口（正常 LSU）
    val pseudo = Vec(MOV_UNITS, Decoupled(new LsuIssueEntry))           // MOV 发射口
    val freeEntryCount = Output(UInt(log2Ceil(ISSUE_WIDTH + 1).W))//剩余端口数
  }
}

