package rv32isc
import chisel3._
import chisel3.util._

import bundles._
import config.LWB_InstructionConstants._ 
import config.Configs._
import config.InstructionConstants._

// 纯访存模块 - 作为LSU的子模块使用
class MemoryAccessUnit extends Module {
  val io = IO(new MemoryAccessIO)
  
  // 数据处理逻辑
  val byteOffset = io.in.addr(DATA_BYTE_WIDTH_LOG-1, 0)
  
  // 读取数据处理
  val processedReadData = WireDefault(io.in.rdata)
  when(io.in.ren) {
    switch(io.in.funct3) {
      // LB - 加载字节(带符号扩展)
      is(F3_LB) {
        val byteShift = byteOffset << 3
        val byteData = (io.in.rdata >> byteShift)(7, 0)
        processedReadData := Cat(Fill(24, byteData(7)), byteData)
      }
      // LH - 加载半字(带符号扩展)
      is(F3_LH) {
        val halfwordShift = Cat(byteOffset(1), 0.U(4.W))
        val halfwordData = (io.in.rdata >> halfwordShift)(15, 0)
        processedReadData := Cat(Fill(16, halfwordData(15)), halfwordData)
      }
      // LW - 加载字
      is(F3_LW) {
        processedReadData := io.in.rdata
      }
      // LBU - 加载字节(无符号扩展)
      is(F3_LBU) {
        val byteShift = byteOffset << 3
        val byteData = (io.in.rdata >> byteShift)(7, 0)
        processedReadData := Cat(0.U(24.W), byteData)
      }
      // LHU - 加载半字(无符号扩展)
      is(F3_LHU) {
        val halfwordShift = Cat(byteOffset(1), 0.U(4.W))
        val halfwordData = (io.in.rdata >> halfwordShift)(15, 0)
        processedReadData := Cat(0.U(16.W), halfwordData)
      }
    }
  }
  
  // 写入数据处理
  val processedWriteData = WireDefault(io.in.wdata)
  when(io.in.wen) {
    switch(io.in.funct3) {
      // SB - 存储字节
      is(F3_SB) {
        val byteShift = byteOffset << 3
        val byteMask = (0xff.U(32.W)) << byteShift
        val byteData = (io.in.wdata(7, 0)) << byteShift
        processedWriteData := (io.in.rdata & ~byteMask) | (byteData & byteMask)
      }
      // SH - 存储半字
      is(F3_SH) {
        val halfwordShift = byteOffset(1) << 4
        val halfwordMask = (0xffff.U(32.W)) << halfwordShift
        val shiftedHalfword = (io.in.wdata(15, 0) << halfwordShift)(31, 0)
        processedWriteData := (io.in.rdata & ~halfwordMask) | (shiftedHalfword & halfwordMask)
      }
    }
  }
  
  // 输出连接
  io.out.addr := io.in.addr
  io.out.ren := io.in.ren
  io.out.wen := io.in.wen
  io.out.mask := io.in.mask
  io.out.wdata := processedWriteData
  io.out.rdata := processedReadData
}