package rv32isc
import chisel3._
import chisel3.util._

import bundles._
import config.Configs._
import config.OoOParams._

// 地址计算单元，用于LSU内部
class LSUAddressUnit extends Module {
  val io = IO(new LSUAddressIO)

  // 地址计算：基地址 + 偏移
  val computedAddr = io.in.rs1_data + io.in.imm

  // 地址有效性判断
  def isValidAddress(addr: UInt, isStore: Bool): Bool = {
    // 判断地址是否有效的逻辑
    val iromValid = (addr >= 0x80000000.U) && (addr < 0x80010000.U) && !isStore
    val dramValid = (addr >= 0x80100000.U) && (addr < 0x80140000.U)
    val peripValid = (addr >= 0x80200000.U) && (addr < 0x80200100.U)

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

// 优化的LSU主模块，支持StoreQueue和伪指令处理
class LSU extends Module {
  val io = IO(new LSUWithStoreQueueIO)  // 使用合并后的接口

  // 简化为两状态状态机
  val sIdle :: sExec :: Nil = Enum(2)
  val state = RegInit(sIdle)

  // 寄存器定义
  val issueEntryReg = Reg(new LsuIssueEntry)        // 保存当前处理的指令
  val addrResult = Reg(UInt(DATA_WIDTH.W))          // 地址计算结果
  val storeData = Reg(UInt(DATA_WIDTH.W))           // 存储数据值(不是物理寄存器号)

  // 添加回滚范围检查逻辑
  val inRollbackRange = WireDefault(false.B)
  when(io.rollback.valid) {
    val rollbackIdx = io.rollback.bits
    val tailIdx = io.tail
    when(tailIdx >= rollbackIdx) {
      // 普通情况：[rollback, tail)
      inRollbackRange := issueEntryReg.robIdx >= rollbackIdx && issueEntryReg.robIdx < tailIdx
    }.otherwise {
      // 环形情况：tail < rollback
      inRollbackRange := (issueEntryReg.robIdx >= rollbackIdx) || (issueEntryReg.robIdx < tailIdx)
    }
  }

  // 使用MemWithStoreQueue替代直接使用MemoryAccessUnit
  val memWithStoreQueue = Module(new MemWithStoreQueue)
  
  // 连接普通访存接口
  memWithStoreQueue.io.mem.in.addr := addrResult
  memWithStoreQueue.io.mem.in.ren := state === sExec && issueEntryReg.isLoad
  memWithStoreQueue.io.mem.in.wen := state === sExec && issueEntryReg.isStore
  memWithStoreQueue.io.mem.in.mask := addrUnit.io.out.accessMask
  memWithStoreQueue.io.mem.in.wdata := storeData
  memWithStoreQueue.io.mem.in.rdata := io.perip_rdata
  memWithStoreQueue.io.mem.in.funct3 := issueEntryReg.func3
  memWithStoreQueue.io.mem.in.fromStoreQueue := false.B
  memWithStoreQueue.io.mem.in.robIdx := issueEntryReg.robIdx
  
  // 连接外设接口到顶层
  io.perip_addr := memWithStoreQueue.io.perip_addr
  io.perip_ren := memWithStoreQueue.io.perip_ren
  io.perip_wen := memWithStoreQueue.io.perip_wen
  io.perip_mask := memWithStoreQueue.io.perip_mask
  io.perip_wdata := memWithStoreQueue.io.perip_wdata
  memWithStoreQueue.io.perip_rdata := io.perip_rdata

  // 初始化StoreQueue接口
  memWithStoreQueue.io.storeQueue.commitValid := false.B
  memWithStoreQueue.io.storeQueue.commitEntry := DontCare
  
  // 地址计算单元
  val addrUnit = Module(new LSUAddressUnit)
  addrUnit.io.in.rs1_data := Mux(state === sIdle,
                               // 如果是新指令，尝试从旁路获取最新值
                               Mux(io.bypassIn.map(bp => bp.valid && bp.phyDest === io.issue.bits.phyAddrBase).reduce(_ || _),
                                   Mux1H(io.bypassIn.map(bp => bp.valid && bp.phyDest === io.issue.bits.phyAddrBase),
                                         io.bypassIn.map(_.data)),
                                   0.U),  // 正常应该从寄存器文件读取
                               // 使用已保存的地址基值
                               addrResult)
  addrUnit.io.in.imm := Mux(state === sIdle, io.issue.bits.imm, issueEntryReg.imm)
  addrUnit.io.in.funct3 := Mux(state === sIdle, io.issue.bits.func3, issueEntryReg.func3)
  addrUnit.io.in.isLoad := Mux(state === sIdle, io.issue.bits.isLoad, issueEntryReg.isLoad)
  addrUnit.io.in.isStore := Mux(state === sIdle, io.issue.bits.isStore, issueEntryReg.isStore)

  // 默认初始化输出
  io.issue.ready := state === sIdle
  io.bypassOut.valid := false.B
  io.bypassOut.phyDest := 0.U
  io.bypassOut.data := 0.U
  io.bypassOut.robIdx := 0.U

  io.resultOut.valid := false.B
  io.resultOut.bits.valid := false.B
  io.resultOut.bits.phyDest := 0.U
  io.resultOut.bits.data := 0.U
  io.resultOut.bits.robIdx := 0.U

  // 伪指令输出端口初始化
  io.pseudoOut.valid := false.B
  io.pseudoOut.bits.valid := false.B
  io.pseudoOut.bits.phyDest := 0.U
  io.pseudoOut.bits.data := 0.U
  io.pseudoOut.bits.robIdx := 0.U

  // 外设接口默认值
  io.perip_addr := 0.U
  io.perip_ren := false.B
  io.perip_wen := false.B
  io.perip_mask := 0.U
  io.perip_wdata := 0.U

  // 忙状态信号
  io.busy := state === sExec

  // 状态转换和数据处理
  switch(state) {
    is(sIdle) {
      when(io.issue.valid) {
        val isPseudoMov = io.issue.bits.isPseudoMov

        when(isPseudoMov) {
          // === 处理伪mov指令 ===
          // 从旁路获取源数据
          val pseudoData = Mux(io.bypassIn.map(bp => bp.valid && bp.phyDest === io.issue.bits.dataOrPseudoSrc).reduce(_ || _),
                              Mux1H(io.bypassIn.map(bp => bp.valid && bp.phyDest === io.issue.bits.dataOrPseudoSrc),
                                   io.bypassIn.map(_.data)),
                              0.U) // 正常情况下应该从寄存器文件读取

          // 掩码计算逻辑 - 根据func3进行掩码处理
          val maskedData = WireDefault(pseudoData)
          switch(io.issue.bits.func3) {
            // 字节操作 (LB/LBU/SB)
            is(F3_LB) { 
              // 有符号扩展
              val byteData = pseudoData(7, 0)
              maskedData := Cat(Fill(24, byteData(7)), byteData)
            }
            is(F3_LBU) { 
              // 无符号扩展
              val byteData = pseudoData(7, 0)
              maskedData := Cat(0.U(24.W), byteData)
            }
            // 半字操作 (LH/LHU/SH)
            is(F3_LH) { 
              // 有符号扩展
              val halfwordData = pseudoData(15, 0)
              maskedData := Cat(Fill(16, halfwordData(15)), halfwordData)
            }
            is(F3_LHU) { 
              // 无符号扩展
              val halfwordData = pseudoData(15, 0)
              maskedData := Cat(0.U(16.W), halfwordData)
            }
            // 字操作 (LW/SW) - 默认情况
            is(F3_LW) { 
              maskedData := pseudoData
            }
          }

          // 直接输出伪mov结果
          io.pseudoOut.valid := true.B
          io.pseudoOut.bits.valid := true.B
          io.pseudoOut.bits.phyDest := io.issue.bits.phyRd
          io.pseudoOut.bits.data := maskedData
          io.pseudoOut.bits.robIdx := io.issue.bits.robIdx

          // 保持idle状态，无需进入执行状态
          state := sIdle

        }.otherwise {
          // === 处理普通load/store指令 ===
          // 保存当前指令到寄存器
          issueEntryReg := io.issue.bits

          // 尝试获取store的数据
          when(io.issue.bits.isStore) {
            when(io.bypassIn.map(bp => bp.valid && bp.phyDest === io.issue.bits.dataOrPseudoSrc).reduce(_ || _)) {
              storeData := Mux1H(io.bypassIn.map(bp => bp.valid && bp.phyDest === io.issue.bits.dataOrPseudoSrc),
                                io.bypassIn.map(_.data))
            }.otherwise {
              storeData := 0.U  // 正常应该从寄存器文件读取
            }
          }

          // 如果地址计算有效且没有外设访问，进行旁路
          when(addrUnit.io.out.valid && addrUnit.io.out.canAccess) {
            // 保存地址计算结果
            addrResult := addrUnit.io.out.addr

            // 发送地址计算结果到旁路总线
            io.bypassOut.valid := true.B
            io.bypassOut.phyDest := io.issue.bits.phyAddrBase
            io.bypassOut.data := addrUnit.io.out.addr
            io.bypassOut.robIdx := io.issue.bits.robIdx

            // 添加回滚检查 - 如果指令在回滚范围内，不进入执行状态
            when(!inRollbackRange) {
              // 如果是外设访问或需要进一步处理，转入执行状态
              state := sExec
            }
          }.otherwise {
            // 无效地址，发送错误
            // 这里可以添加异常处理
            state := sIdle
          }
        }
      }
    }

    is(sExec) {
      // 如果检测到回滚且当前指令在回滚范围内，放弃执行
      when(inRollbackRange) {
        state := sIdle
      }.otherwise {
        // 处理load/store指令
        when(issueEntryReg.isLoad) {
          // === Load指令处理 ===
          // 处理外设读取
          when(addrResult >= 0x80200000.U && addrResult < 0x80200100.U) {
            // 外设读取
            memWithStoreQueue.io.mem.in.ren := true.B
            memWithStoreQueue.io.mem.in.rdata := io.perip_rdata  // 外设数据
            
            // 设置结果
            io.resultOut.valid := true.B
            io.resultOut.bits.valid := true.B
            io.resultOut.bits.phyDest := issueEntryReg.phyRd
            io.resultOut.bits.data := memWithStoreQueue.io.mem.out.rdata
            io.resultOut.bits.robIdx := issueEntryReg.robIdx
            
            state := sIdle
          }.otherwise {
            // 内存读取 - 假设通过同一接口但访问不同地址空间
            memWithStoreQueue.io.mem.in.ren := true.B
            // 这里应该连接到内存接口，但当前似乎复用了外设接口
            // 在实际系统中需要修改
            
            // 设置结果
            io.resultOut.valid := true.B
            io.resultOut.bits.valid := true.B
            io.resultOut.bits.phyDest := issueEntryReg.phyRd
            io.resultOut.bits.data := memWithStoreQueue.io.mem.out.rdata
            io.resultOut.bits.robIdx := issueEntryReg.robIdx
            
            state := sIdle
          }
        }.elsewhen(issueEntryReg.isStore) {
          // === Store指令处理 ===
          // 注意：此处仍需要使用StoreQueue，因为MemWithStoreQueue只处理提交的存储
          
          // 使用MemWithStoreQueue的StoreQueue接口
          // 注意：这里不使用MemWithStoreQueue的StoreQueue接口
          // 因为我们仍需要先将Store指令加入队列，而MemWithStoreQueue负责从队列提交
          
          // 创建新的StoreQueue实例 - 这里实际上应该是访问全局的StoreQueue
          val storeQueue = Module(new StoreQueue)
          
          // 回滚信号连接
          storeQueue.io.in.rollback := io.rollback.valid
          storeQueue.io.in.rollbackTarget := io.rollback.bits
          
          // 添加到StoreQueue
          storeQueue.io.in.enq.valid := true.B
          storeQueue.io.in.enq.bits.robIdx := issueEntryReg.robIdx
          storeQueue.io.in.enq.bits.addr := addrResult
          storeQueue.io.in.enq.bits.data := storeData
          storeQueue.io.in.enq.bits.mask := addrUnit.io.out.accessMask

          when(storeQueue.io.in.enq.ready) {
            // 设置结果
            io.resultOut.valid := true.B
            io.resultOut.bits.valid := true.B
            io.resultOut.bits.phyDest := 0.U  // store不需要写回
            io.resultOut.bits.data := 0.U
            io.resultOut.bits.robIdx := issueEntryReg.robIdx

            // 完成后回到空闲状态
            state := sIdle
          }
        }
      }
    }
  }

  // 默认条件下resultOut和pseudoOut都准备好接收下一个结果
  io.resultOut.ready := true.B
  io.pseudoOut.ready := true.B
}