package rv32isc
import chisel3._
import chisel3.util._

import bundles._
import config.Configs._
import config.OoOParams._
import config.LWB_InstructionConstants._

// 地址计算单元，用于LSU内部
class LSUAddressUnit extends Module {
  val io = IO(new LSUAddressIO)

  // 地址计算：基地址 + 偏移
  val computedAddr = io.in.rs1_data + io.in.imm

  // 地址有效性判断
def isValidAddress(addr: UInt, isStore: Bool): Bool = {
  val iromValid   = (addr >= 0x80000000L.U) && (addr < 0x80010000L.U) && !isStore
  val dramValid   = (addr >= 0x80100000L.U) && (addr < 0x80140000L.U)
  val peripValid  = (addr >= 0x80200000L.U) && (addr < 0x80200100L.U)
  iromValid || dramValid || peripValid
}

  // 访问掩码计算
  val accessMask = WireDefault("b10".U(2.W))
  switch(io.in.funct3) {
    is(F3_LB, F3_LBU, F3_SB) { accessMask := "b00".U }
    is(F3_LH, F3_LHU, F3_SH) { accessMask := "b01".U }
    is(F3_LW, F3_SW) { accessMask := "b10".U }
  }

  // 外设访问检查（只有DRAM支持非字访问）
  val isDRAM = (computedAddr >= 0x80100000L.U) && (computedAddr < 0x80140000L.U)
  val isPerip = (computedAddr >= 0x80200000L.U) && (computedAddr < 0x80200100L.U)
  val maskValid = isDRAM || (isPerip && (accessMask === "b10".U))

  // 输出
  io.out.addr := computedAddr
  io.out.valid := true.B  // 基本有效性
  io.out.accessMask := accessMask
  io.out.canAccess := isValidAddress(computedAddr, io.in.isStore) && maskValid
}


class LSU extends Module {
  val io = IO(new LSUWithStoreQueueIO)

  //-------------------------------------
  // 流水线寄存器（仿RS_lsu_Reg设计）
  //-------------------------------------
  class LSUPipelineReg extends Bundle {
    val entry = new LsuIssueEntry
    val addrResult = UInt(DATA_WIDTH.W)
    val storeData = UInt(DATA_WIDTH.W)
    val accessMask = UInt(2.W)
    val canAccess = Bool()
  }

  val reg_valid = RegInit(false.B)
  val reg_data = RegInit(0.U.asTypeOf(new LSUPipelineReg))
  val reg_rollback = RegInit(false.B)  // 回滚状态寄存器

  // 回滚范围判断（复用原逻辑）
  val inRollbackRange = Wire(Bool())
  when(io.rollback.valid) {
    val rollbackIdx = io.rollback.bits.rollbackIdx
    val tailIdx = io.rollback.bits.tailIdx
    when(tailIdx >= rollbackIdx) {
      inRollbackRange := reg_data.entry.robIdx >= rollbackIdx && reg_data.entry.robIdx < tailIdx
    }.otherwise {
      inRollbackRange := (reg_data.entry.robIdx >= rollbackIdx) || (reg_data.entry.robIdx < tailIdx)
    }
  }.otherwise {
    inRollbackRange := false.B
  }

  // 流水线控制信号
  val stall = false.B
  val flush = inRollbackRange || io.rollback.valid  // 回滚触发冲刷

  //-------------------------------------
  // 子模块实例化
  //-------------------------------------
  val addrUnit = Module(new LSUAddressUnit)
  val globalStoreQueue = Module(new StoreQueue)
  val memWithStoreQueue = Module(new MemWithStoreQueue)

  // 将ROB提交信号连接到StoreQueue
  globalStoreQueue.io.in.commit := io.commit
  // 新增: 连接 StoreQueue 头部信息到 LSU 输出
  io.sq_head_valid := globalStoreQueue.io.out.commitValid
  io.sq_head_robIdx := globalStoreQueue.io.out.commitEntry.robIdx

  // 连接外设接口到顶层（保持不变）
//    io.perip_addr := memWithStoreQueue.io.perip_addr
//    io.perip_ren := memWithStoreQueue.io.perip_ren
//    io.perip_wen := memWithStoreQueue.io.perip_wen
//    io.perip_mask := memWithStoreQueue.io.perip_mask
//    io.perip_wdata := memWithStoreQueue.io.perip_wdata
//    memWithStoreQueue.io.perip_rdata := io.perip_rdata

  //-------------------------------------
  // 地址计算阶段
  //-------------------------------------

  addrUnit.io.in.rs1_data := io.issue.bits.AddrBaseData
  addrUnit.io.in.imm := io.issue.bits.imm
  addrUnit.io.in.funct3 := io.issue.bits.func3
  addrUnit.io.in.isLoad := io.issue.bits.isLoad
  addrUnit.io.in.isStore := io.issue.bits.isStore

  // 地址旁路输出（保持原接口）
  io.bypassOut.valid := io.issue.valid && addrUnit.io.out.valid
  io.bypassOut.reg.phyDest := io.issue.bits.phyAddrBaseDest
  io.bypassOut.reg.robIdx := io.issue.bits.robIdx
  io.bypassOut.data := addrUnit.io.out.addr


  //-------------------------------------
  // 流水寄存器更新逻辑
  //-------------------------------------
  io.issue.ready := !reg_valid || !stall

  when (flush) {
    reg_data := 0.U.asTypeOf(new LSUPipelineReg)
    reg_valid := false.B
  }.elsewhen (!stall) {
    reg_valid := io.issue.valid
    when(io.issue.valid) {
        reg_data.entry := io.issue.bits
        reg_data.addrResult := addrUnit.io.out.addr
        reg_data.accessMask := addrUnit.io.out.accessMask
        reg_data.canAccess := addrUnit.io.out.canAccess
        reg_data.storeData := io.issue.bits.StoreData
    }
  }

  //-------------------------------------
  // 访存执行阶段(第二周期) 
  //-------------------------------------
    // --- 伪指令处理逻辑 (第二周期) ---
  val pseudoData = reg_data.storeData
  val pseudoResult = WireDefault(pseudoData)
  when(reg_data.entry.isMov) {
    switch(reg_data.entry.func3) {
      is(F3_LB)  { pseudoResult := Cat(Fill(24, pseudoData(7)), pseudoData(7, 0)) }
      is(F3_LBU) { pseudoResult := Cat(0.U(24.W), pseudoData(7, 0)) }
      is(F3_LH)  { pseudoResult := Cat(Fill(16, pseudoData(15)), pseudoData(15, 0)) }
      is(F3_LHU) { pseudoResult := Cat(0.U(16.W), pseudoData(15, 0)) }
      is(F3_LW)  { pseudoResult := pseudoData }
    }
  }
  
  // StoreQueue接口
  globalStoreQueue.io.in.rollback := io.rollback
  globalStoreQueue.io.in.enq.valid := reg_valid && reg_data.entry.isStore
  globalStoreQueue.io.in.enq.bits.robIdx := reg_data.entry.robIdx
  globalStoreQueue.io.in.enq.bits.addr := reg_data.addrResult
  globalStoreQueue.io.in.enq.bits.data := reg_data.storeData
  globalStoreQueue.io.in.bypassAddr.valid := reg_valid && reg_data.entry.isLoad
  globalStoreQueue.io.in.bypassAddr.bits := reg_data.addrResult

  // 访存单元连接
  memWithStoreQueue.io.mem.in.addr := reg_data.addrResult
  memWithStoreQueue.io.mem.in.ren := reg_valid && reg_data.entry.isLoad
  memWithStoreQueue.io.mem.in.wen := false.B  // Store通过StoreQueue提交
  memWithStoreQueue.io.mem.in.mask := reg_data.accessMask
  memWithStoreQueue.io.mem.in.wdata := reg_data.storeData
  memWithStoreQueue.io.mem.in.funct3 := reg_data.entry.func3
  memWithStoreQueue.io.mem.in.fromStoreQueue := false.B
  memWithStoreQueue.io.mem.in.robIdx := reg_data.entry.robIdx
  memWithStoreQueue.io.storeQueue.commitValid := io.commit

// 结果输出逻辑 (统一在第二周期输出)
  val loadResult = Mux(globalStoreQueue.io.out.bypass.hit,
                      globalStoreQueue.io.out.bypass.data,
                      memWithStoreQueue.io.mem.out.rdata)
  
  val finalResult = Mux(reg_data.entry.isMov, pseudoResult, loadResult)

  io.resultOut.valid := reg_valid && (reg_data.entry.isLoad || reg_data.entry.isStore || reg_data.entry.isMov)
  io.resultOut.bits.valid := reg_valid && (reg_data.entry.isLoad || reg_data.entry.isStore || reg_data.entry.isMov)
  io.resultOut.bits.reg.phyDest := Mux(reg_data.entry.isStore, 0.U, reg_data.entry.phyRd)
  io.resultOut.bits.data := Mux(reg_data.entry.isStore, 0.U, finalResult)
  io.resultOut.bits.reg.robIdx := reg_data.entry.robIdx

  // 写回旁路总线 (Load和伪指令都需要写回)
  io.writebackBus.valid := reg_valid && (reg_data.entry.isLoad || reg_data.entry.isMov)
  io.writebackBus.reg.phyDest := reg_data.entry.phyRd
  io.writebackBus.reg.robIdx := reg_data.entry.robIdx
  io.writebackBus.data := finalResult

  // 忙状态信号
  io.busy := reg_valid
}

class LSU_Top extends Module {
  val io = IO(new Bundle {
    // 从保留站接收指令
    val issue = Flipped(Decoupled(new LsuIssueEntry))

    // 旁路总线接口
    val bypassOut = Output(new BypassBus)

    // 结果输出接口
    val resultOut = Output(new BypassBus)

    // 写回旁路接口
    val writebackBus = Output(new WritebackBus)

    // 外设直连接口
//      val perip_addr = Output(UInt(32.W))
//      val perip_ren = Output(Bool())
//      val perip_wen = Output(Bool())
//      val perip_mask = Output(UInt(2.W))
//      val perip_wdata = Output(UInt(32.W))
//      val perip_rdata = Input(UInt(32.W))

    // 回滚信号
    val rollback = Input(Valid(new RsRollbackEntry))

    // 新增: 来自ROB的Store提交请求
    val commit_store = Input(ValidIO(new RobCommitStoreEntry))

    val busy = Output(Bool())
  })

  // 实例化RS_lsu_Reg，负责从保留站接收指令
  val rs_lsu_reg = Module(new RS_lsu_Reg)

  // 实例化LSU核心执行单元
  val lsu_core = Module(new LSU)

  // 实例化lsu_Next_Reg，负责将执行结果输出
  val lsu_next_reg = Module(new LSU_Next_Reg)

  // --- 新增的提交匹配逻辑 ---
  // 检查ROB发出的store提交请求
  val do_commit = io.commit_store.valid && lsu_core.io.sq_head_valid && (io.commit_store.bits.robIdx === lsu_core.io.sq_head_robIdx)

  // 当ROB的提交请求有效，且LSU的StoreQueue头部也有效，并且两者的robIdx匹配时，向LSU核心发送commit信号
  lsu_core.io.commit := do_commit
  // --- 逻辑添加完毕 ---

  // 回滚相关信号处理

  // ========= RS_lsu_Reg连接 =========
  rs_lsu_reg.io.in <> io.issue
  rs_lsu_reg.io.stall := false.B
  rs_lsu_reg.io.flush := false.B
  rs_lsu_reg.io.rollback:= io.rollback

  // ========= LSU核心单元连接 =========
  lsu_core.io.issue <> rs_lsu_reg.io.out
  lsu_core.io.rollback := io.rollback

  // LSU外设接口连接
//    io.perip_addr := lsu_core.io.perip_addr
//    io.perip_ren := lsu_core.io.perip_ren
//    io.perip_wen := lsu_core.io.perip_wen
//    io.perip_mask := lsu_core.io.perip_mask
//    io.perip_wdata := lsu_core.io.perip_wdata
//    lsu_core.io.perip_rdata := io.perip_rdata

  // ========= lsu_Next_Reg连接 =========
  // lsu_Next_Reg输入连接
  lsu_next_reg.io.in <> lsu_core.io.resultOut
  lsu_next_reg.io.stall := false.B  // 这里可以根据实际需求调整
  lsu_next_reg.io.flush := false.B  // 这里可以根据实际需求调整
  lsu_next_reg.io.rollback := io.rollback

  // ========= 输出连接 =========
  // 第一周期组合逻辑结束后输出的Bypass旁路信号
  io.bypassOut := lsu_core.io.bypassOut

  // 伪指令结果直接输出，不经过lsu_Next_Reg
  // io.pseudoOut := lsu_core.io.pseudoOut

  // 经过第二周期后输出的写回结果
  io.resultOut := lsu_next_reg.io.out

  // 第二周期访存结束后输出的WritebackBus写回旁路信号
  // 从lsu_core的writebackBus获取数据，经过lsu_Next_Reg处理
  io.writebackBus.valid := lsu_next_reg.io.rob_wb.valid
  io.writebackBus.reg.phyDest := lsu_next_reg.io.out.reg.phyDest
  io.writebackBus.reg.robIdx := lsu_next_reg.io.out.reg.robIdx
  io.writebackBus.data := lsu_next_reg.io.out.data

  // 忙状态信号 - LSU至少有一个阶段在处理指令时就是忙的
  io.busy := rs_lsu_reg.io.out.valid || lsu_core.io.busy
}
