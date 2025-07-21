package rv32isc
import chisel3._
import chisel3.util._

import bundles._
import config.LWB_InstructionConstants._
import config.Configs._
import config.InstructionConstants._
import config.OoOParams._

// LSU地址计算模块
class LSUAddressUnit extends Module {
  val io = IO(new LSUAddressIO)

  // 地址计算
  val computedAddr = io.in.rs1_data + io.in.imm

  // 地址有效性检查
  def isValidAddress(addr: UInt, isWrite: Bool): Bool = {
    val isIROM = (addr >= 0x80000000.U) && (addr < 0x80004000.U)
    val isDRAM = (addr >= 0x80100000.U) && (addr < 0x80140000.U)
    val isPerip = (addr >= 0x80200000.U) && (addr < 0x80200100.U)

    // IROM只读，不能写
    val iromValid = isIROM && !isWrite
    // DRAM支持读写
    val dramValid = isDRAM
    // 外设支持读写但需要4字节对齐
    val peripValid = isPerip && (addr(1,0) === 0.U)

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
  val isDRAM = (computedAddr >= 0x80100000.U) && (computedAddr < 0x80140000.U)
  val isPerip = (computedAddr >= 0x80200000.U) && (computedAddr < 0x80200100.U)
  val maskValid = isDRAM || (isPerip && (accessMask === "b10".U))

  // 输出
  io.out.addr := computedAddr
  io.out.valid := true.B  // 基本有效性
  io.out.accessMask := accessMask
  io.out.canAccess := isValidAddress(computedAddr, io.in.isStore) && maskValid
}

// 优化的LSU主模块，集成了StoreQueue支持，移除了伪指令处理
class LSU extends Module {
  val io = IO(new LSUWithStoreQueueIO)

  // 简化为两状态状态机
  val sIdle :: sExec :: Nil = Enum(2)
  val state = RegInit(sIdle)

  // 寄存器定义
  val issueEntryReg = Reg(new LsuIssueEntry)        // 保存当前处理的指令
  val addrResult = Reg(UInt(DATA_WIDTH.W))          // 地址计算结果
  val storeData = Reg(UInt(DATA_WIDTH.W))           // 存储数据值(不是物理寄存器号)

  // StoreQueue实例化
  val storeQueue = Module(new StoreQueue)

  // 回滚信号连接
  storeQueue.io.in.rollback := io.rollback.valid
  storeQueue.io.in.rollbackTarget := io.rollback.bits

  // 地址计算单元
  val addrUnit = Module(new LSUAddressUnit)
  addrUnit.io.in.rs1_data := Mux(state === sIdle,
                               // 如果是新指令，尝试从旁路获取最新值
                               Mux(io.bypassIn.map(bp => bp.valid && bp.phyDest === io.issue.bits.phyAddrBase).reduce(_ || _),
                                   Mux1H(io.bypassIn.map(bp => bp.valid && bp.phyDest === io.issue.bits.phyAddrBase),
                                         io.bypassIn.map(_.data)),
                                   0.U), // 如果没有旁路命中，正常情况下应该从寄存器文件读取
                               // 如果已经在执行，使用寄存的指令
                               addrResult)
  addrUnit.io.in.imm := Mux(state === sIdle, io.issue.bits.imm, issueEntryReg.imm)
  addrUnit.io.in.funct3 := Mux(state === sIdle, io.issue.bits.func3, issueEntryReg.func3)
  addrUnit.io.in.isLoad := Mux(state === sIdle, io.issue.bits.isLoad, issueEntryReg.isLoad)
  addrUnit.io.in.isStore := Mux(state === sIdle, io.issue.bits.isStore, issueEntryReg.isStore)

  // 访存单元
  val memUnit = Module(new MemoryAccessUnit)
  memUnit.io.in.addr := addrResult
  memUnit.io.in.mask := addrUnit.io.out.accessMask
  memUnit.io.in.ren := issueEntryReg.isLoad
  memUnit.io.in.wen := false.B  // 在LSU阶段不直接写入，而是放入StoreQueue
  memUnit.io.in.wdata := storeData
  memUnit.io.in.rdata := io.perip_rdata
  memUnit.io.in.funct3 := issueEntryReg.func3
  memUnit.io.in.fromStoreQueue := false.B
  memUnit.io.in.robIdx := issueEntryReg.robIdx

  // StoreQueue连接
  // 入队逻辑
  storeQueue.io.in.enq.valid := issueEntryReg.isStore && state === sExec
  storeQueue.io.in.enq.bits.addr := addrResult
  storeQueue.io.in.enq.bits.data := storeData
  storeQueue.io.in.enq.bits.robIdx := issueEntryReg.robIdx

  // 旁路查询逻辑
  storeQueue.io.in.bypassAddr.valid := issueEntryReg.isLoad && state === sExec
  storeQueue.io.in.bypassAddr.bits := addrResult

  // 获取StoreQueue旁路结果
  val bypassHit = storeQueue.io.out.bypass.hit
  val bypassData = storeQueue.io.out.bypass.data

  // 处理从StoreQueue提交到内存的store指令
  when(storeQueue.io.out.commitValid) {
    io.perip_addr := storeQueue.io.out.commitEntry.addr
    io.perip_wen := true.B
    io.perip_mask := "b10".U // 默认为字访问
    io.perip_wdata := storeQueue.io.out.commitEntry.data
  }

  // 初始化默认输出
  io.bypassOut.valid := false.B
  io.bypassOut.phyDest := 0.U
  io.bypassOut.data := 0.U
  io.bypassOut.robIdx := 0.U

  io.resultOut.valid := false.B
  io.resultOut.bits.valid := false.B
  io.resultOut.bits.phyDest := 0.U
  io.resultOut.bits.data := 0.U
  io.resultOut.bits.robIdx := 0.U

  io.perip_addr := 0.U
  io.perip_ren := false.B
  io.perip_wen := false.B
  io.perip_mask := 0.U
  io.perip_wdata := 0.U

  io.busy := state === sExec
  io.issue.ready := state === sIdle

  // 状态机逻辑
  switch(state) {
    is(sIdle) {
      when(io.issue.valid && !io.issue.bits.isPseudoMov) { // 过滤掉伪指令
        // 保存指令到寄存器
        issueEntryReg := io.issue.bits

        // 计算地址并立即通过旁路总线广播
        addrResult := addrUnit.io.out.addr
        io.bypassOut.valid := true.B
        io.bypassOut.phyDest := io.issue.bits.phyAddrBase
        io.bypassOut.data := addrUnit.io.out.addr
        io.bypassOut.robIdx := io.issue.bits.robIdx

        // 获取存储数据(对于store指令)
        when(io.issue.bits.isStore) {
          // 尝试从旁路获取最新值
          when(io.bypassIn.map(bp => bp.valid && bp.phyDest === io.issue.bits.phyStoreData).reduce(_ || _)) {
            storeData := Mux1H(io.bypassIn.map(bp => bp.valid && bp.phyDest === io.issue.bits.phyStoreData),
                               io.bypassIn.map(_.data))
          }.otherwise {
            storeData := 0.U // 正常情况下应该从寄存器文件读取
          }
        }

        // 进入执行状态
        state := sExec
      }
    }

    is(sExec) {
      // 执行访存操作
      io.perip_addr := addrResult
      io.perip_ren := issueEntryReg.isLoad
      io.perip_wen := false.B  // 在LSU阶段不直接写入，而是放入StoreQueue
      io.perip_mask := addrUnit.io.out.accessMask
      io.perip_wdata := storeData

      // 输出结果
      when(issueEntryReg.isLoad) {
        io.resultOut.valid := true.B
        io.resultOut.bits.valid := true.B
        io.resultOut.bits.phyDest := issueEntryReg.phyRd
        // 如果命中StoreQueue中的条目，使用StoreQueue中的数据；否则使用从内存读取的数据
        io.resultOut.bits.data := Mux(bypassHit, bypassData, memUnit.io.out.rdata)
        io.resultOut.bits.robIdx := issueEntryReg.robIdx
      }.elsewhen(issueEntryReg.isStore) {
        // Store指令只需要通知ROB它已完成计算，实际写内存由StoreQueue在提交时完成
        io.resultOut.valid := true.B
        io.resultOut.bits.valid := true.B
        io.resultOut.bits.phyDest := 0.U  // 存储指令不写寄存器
        io.resultOut.bits.data := 0.U
        io.resultOut.bits.robIdx := issueEntryReg.robIdx
      }

      // 当下游接收结果后，返回空闲状态
      when(io.resultOut.ready) {
        state := sIdle
      }
    }
  }
}