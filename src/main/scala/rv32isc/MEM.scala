package rv32isc
import chisel3._
import chisel3.util._

import bundles._
import config.LWB_InstructionConstants._
import config.Configs._
import config.InstructionConstants._
import config.OoOParams._

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
  io.out.done := true.B  // 默认认为访存操作一个周期完成
}

// 增强版MEM模块 - 支持StoreQueue提交
class MemWithStoreQueue extends Module {
  val io = IO(new Bundle {
    // 普通访存接口
    val mem = new MemoryAccessIO

    // 外设接口
    val perip_addr = Output(UInt(32.W))
    val perip_ren = Output(Bool())
    val perip_wen = Output(Bool())
    val perip_mask = Output(UInt(2.W))
    val perip_wdata = Output(UInt(32.W))
    val perip_rdata = Input(UInt(32.W))

    // StoreQueue提交接口
    val storeQueue = new Bundle {
      val commitValid = Input(Bool())  // 有效提交信号
      val commitEntry = Input(new StoreEntry)  // 提交的存储条目
    }
  })

  // 内部访存单元
  val memUnit = Module(new MemoryAccessUnit)

  // 默认连接LSU来的正常访存请求
  memUnit.io.in.addr := io.mem.in.addr
  memUnit.io.in.ren := io.mem.in.ren
  memUnit.io.in.wen := io.mem.in.wen
  memUnit.io.in.mask := io.mem.in.mask
  memUnit.io.in.wdata := io.mem.in.wdata
  memUnit.io.in.rdata := io.perip_rdata
  memUnit.io.in.funct3 := io.mem.in.funct3
  memUnit.io.in.fromStoreQueue := false.B
  memUnit.io.in.robIdx := io.mem.in.robIdx

  // 优先处理来自StoreQueue的提交请求
  when(io.storeQueue.commitValid) {
    memUnit.io.in.addr := io.storeQueue.commitEntry.addr
    memUnit.io.in.ren := false.B
    memUnit.io.in.wen := true.B
    // 基于地址设置适当的掩码，这里简化为字访问
    memUnit.io.in.mask := "b10".U
    memUnit.io.in.wdata := io.storeQueue.commitEntry.data
    memUnit.io.in.fromStoreQueue := true.B
    memUnit.io.in.robIdx := io.storeQueue.commitEntry.robIdx
  }

  // 连接到外设接口
  io.perip_addr := memUnit.io.out.addr
  io.perip_ren := memUnit.io.out.ren
  io.perip_wen := memUnit.io.out.wen
  io.perip_mask := memUnit.io.out.mask
  io.perip_wdata := memUnit.io.out.wdata

  // 返回结果给LSU
  io.mem.out := memUnit.io.out
}
