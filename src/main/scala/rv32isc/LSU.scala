package rv32isc
import chisel3._
import chisel3.util._

import bundles._
import config.LWB_InstructionConstants._
import config.Configs._
import config.InstructionConstants._

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
/* 没有状态机和握手协议的LSU主模块设计

// LSU主模块（两周期操作）
class LSU extends Module {
  val io = IO(new Bundle {
    val addrIn = Input(new LSUAddressInput)
    val addrOut = Output(new LSUAddressOutput)
    val memIn = Input(new MemoryAccessInput)
    val memOut = Output(new MemoryAccessOutput)
    val perip_addr = Output(UInt(32.W))
    val perip_ren = Output(Bool())
    val perip_wen = Output(Bool())
    val perip_mask = Output(UInt(2.W))
    val perip_wdata = Output(UInt(32.W))
    val perip_rdata = Input(UInt(32.W))
    // 新增：周期间使能信号，外部控制LSU流水线寄存器是否更新
    val lsu_enable = Input(Bool()) // true: 正常推进，false: 保持上周期结果（如暂停/冒险）
  })

  // 地址计算单元
  val addrUnit = Module(new LSUAddressUnit)
  addrUnit.io.in := io.addrIn
  io.addrOut := addrUnit.io.out

  // 修改：流水线寄存器增加使能信号
  // 当lsu_enable为true时，寄存器更新为新计算结果；为false时保持上周期值
  val addrReg = RegEnable(addrUnit.io.out, io.lsu_enable)
  // 说明：lsu_enable用于控制LSU两个周期之间的数据流动，支持暂停/冲刷等功能

  // 访存单元输入由寄存器提供
  val memUnit = Module(new MemoryAccessUnit)
  memUnit.io.in.addr := addrReg.addr
  memUnit.io.in.mask := addrReg.accessMask
  memUnit.io.in.ren := io.memIn.ren
  memUnit.io.in.wen := io.memIn.wen
  memUnit.io.in.wdata := io.memIn.wdata
  memUnit.io.in.rdata := io.memIn.rdata
  memUnit.io.in.funct3 := io.memIn.funct3

  io.memOut := memUnit.io.out

  io.perip_addr := memUnit.io.out.addr
  io.perip_ren := memUnit.io.out.ren
  io.perip_wen := memUnit.io.out.wen
  io.perip_mask := memUnit.io.out.mask
  io.perip_wdata := memUnit.io.out.wdata
}

*/