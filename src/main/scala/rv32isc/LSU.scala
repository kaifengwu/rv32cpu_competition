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

// 优化的LSU主模块
class LSU extends Module {
  val io = IO(new Bundle {
    // 从保留站接收指令
    val issue = Flipped(Decoupled(new LsuIssueEntry))
    
    // 旁路总线接口
    val bypassIn = Input(Vec(NUM_BYPASS_PORTS, new BypassBus))   // 接收前馈数据
    val bypassOut = Output(new BypassBus)                        // 输出地址计算结果
    
    // 结果输出接口
    val resultOut = Decoupled(new BypassBus)                     // 最终结果输出
    
    // 外设直连接口
    val perip_addr  = Output(UInt(32.W))
    val perip_ren   = Output(Bool())
    val perip_wen   = Output(Bool())
    val perip_mask  = Output(UInt(2.W))
    val perip_wdata = Output(UInt(32.W))
    val perip_rdata = Input(UInt(32.W))
    
    val busy = Output(Bool())                                    // LSU忙信号
  })

  // 简化为两状态状态机
  val sIdle :: sExec :: Nil = Enum(2)
  val state = RegInit(sIdle)

  // 寄存器定义
  val issueEntryReg = Reg(new LsuIssueEntry)        // 保存当前处理的指令
  val addrResult = Reg(UInt(DATA_WIDTH.W))          // 地址计算结果
  val storeData = Reg(UInt(DATA_WIDTH.W))           // 存储数据值(不是物理寄存器号)

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
  memUnit.io.in.ren := issueEntryReg.isLoad && !issueEntryReg.isPseudoMov
  memUnit.io.in.wen := issueEntryReg.isStore
  memUnit.io.in.wdata := storeData
  memUnit.io.in.rdata := io.perip_rdata
  memUnit.io.in.funct3 := issueEntryReg.func3

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
      when(io.issue.valid) {
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
        
        // 伪MOV优化：直接输出结果不访存
        when(io.issue.bits.isPseudoMov) {
          // 获取伪MOV源数据
          val pseudoData = Mux(io.bypassIn.map(bp => bp.valid && bp.phyDest === io.issue.bits.pseudoSrc).reduce(_ || _),
                              Mux1H(io.bypassIn.map(bp => bp.valid && bp.phyDest === io.issue.bits.pseudoSrc), 
                                    io.bypassIn.map(_.data)),
                              0.U) // 正常情况下应该从寄存器文件读取
          
          // 直接输出结果
          io.resultOut.valid := true.B
          io.resultOut.bits.valid := true.B
          io.resultOut.bits.phyDest := io.issue.bits.phyRd
          io.resultOut.bits.data := pseudoData
          io.resultOut.bits.robIdx := io.issue.bits.robIdx
          
          // 如果下游ready，保持idle状态；否则进入exec状态等待
          state := Mux(io.resultOut.ready, sIdle, sExec)
        }.otherwise {
          // 非伪MOV指令，进入执行状态
          state := sExec
        }
      }
    }
    
    is(sExec) {
      // 执行访存操作
      io.perip_addr := addrResult
      io.perip_ren := issueEntryReg.isLoad && !issueEntryReg.isPseudoMov
      io.perip_wen := issueEntryReg.isStore
      io.perip_mask := addrUnit.io.out.accessMask
      io.perip_wdata := storeData
      
      // 输出结果
      when(issueEntryReg.isLoad) {
        io.resultOut.valid := true.B
        io.resultOut.bits.valid := true.B
        io.resultOut.bits.phyDest := issueEntryReg.phyRd
        io.resultOut.bits.data := memUnit.io.out.rdata
        io.resultOut.bits.robIdx := issueEntryReg.robIdx
      }.elsewhen(issueEntryReg.isStore) {
        io.resultOut.valid := true.B
        io.resultOut.bits.valid := true.B
        io.resultOut.bits.phyDest := 0.U  // 存储指令不写寄存器
        io.resultOut.bits.data := 0.U
        io.resultOut.bits.robIdx := issueEntryReg.robIdx
      }.elsewhen(issueEntryReg.isPseudoMov) {
        // 伪MOV在idle状态就应该处理完成，这里是为了处理下游不ready的情况
        io.resultOut.valid := true.B
        io.resultOut.bits.valid := true.B
        io.resultOut.bits.phyDest := issueEntryReg.phyRd
        io.resultOut.bits.data := storeData // 使用已保存的伪数据
        io.resultOut.bits.robIdx := issueEntryReg.robIdx
      }
      
      // 当下游接收结果后，返回空闲状态
      when(io.resultOut.ready) {
        state := sIdle
      }
    }
  }
}

/*
// LSU主模块（支持DecoupledIO握手协议）
class LSU extends Module {
  val io = IO(new Bundle {
    val addrIn  = Flipped(Decoupled(new LSUAddressInput))      // 地址输入
    val memIn   = Flipped(Decoupled(new MemoryAccessInput))    // 访存输入
    val memOut  = Decoupled(new MemoryAccessOutput)            // 访存输出

    // 外设直连接口
    val perip_addr  = Output(UInt(32.W))
    val perip_ren   = Output(Bool())
    val perip_wen   = Output(Bool())
    val perip_mask  = Output(UInt(2.W))
    val perip_wdata = Output(UInt(32.W))
    val perip_rdata = Input(UInt(32.W))

    val busy = Output(Bool()) // LSU忙信号
  })

  // 状态机
  val sIdle :: sAddr :: sMem :: Nil = Enum(3)
  val state = RegInit(sIdle)

  // 地址阶段寄存器
  val addrReg = Reg(new LSUAddressOutput)
  val memReg  = Reg(new MemoryAccessInput)

  // 地址计算单元
  val addrUnit = Module(new LSUAddressUnit)
  addrUnit.io.in := io.addrIn.bits

  // 访存单元
  val memUnit = Module(new MemoryAccessUnit)
  memUnit.io.in.addr   := addrReg.addr
  memUnit.io.in.mask   := addrReg.accessMask
  memUnit.io.in.ren    := memReg.ren
  memUnit.io.in.wen    := memReg.wen
  memUnit.io.in.wdata  := memReg.wdata
  memUnit.io.in.rdata  := memReg.rdata
  memUnit.io.in.funct3 := memReg.funct3

  // 默认输出
  io.memOut.bits := memUnit.io.out
  io.memOut.valid := (state === sMem)
  io.addrIn.ready := (state === sIdle) || (state === sMem && io.memOut.ready)
  io.memIn.ready  := (state === sAddr)
  io.busy := (state =/= sIdle)

  // 外设直连
  io.perip_addr  := memUnit.io.out.addr
  io.perip_ren   := memUnit.io.out.ren
  io.perip_wen   := memUnit.io.out.wen
  io.perip_mask  := memUnit.io.out.mask
  io.perip_wdata := memUnit.io.out.wdata

  // 状态机流程
  switch(state) {
    is(sIdle) {
      when(io.addrIn.valid) {
        // 地址计算阶段，调用LSUAddressUnit
        addrReg := addrUnit.io.out
        state := sAddr
      }
    }
    is(sAddr) {
      when(io.memIn.valid) {
        // 访存阶段，缓存访存输入
        memReg := io.memIn.bits
        state := sMem
      }
    }
    is(sMem) {
      when(io.memOut.ready && io.addrIn.valid) {
        // 访存结果被取走且有新指令直接进入sAddr状态而不是进入sIdle空状态
        addrReg := addrUnit.io.out
        state := sAddr
      }.elsewhen(io.memOut.ready) {
        // 访存结果被取走，进入空闲状态
        state := sIdle
      }
    }
  }
}
*/