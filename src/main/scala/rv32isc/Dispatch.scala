package rv32isc

import chisel3._
import chisel3.util._

import bundles._
import config.Configs._
import config.OoOParams._

class Dispatch extends Module {
  val io = IO(new DispatchBundleIO)

  for (i <- 0 until ISSUE_WIDTH) {
    val rename = io.in.renameVec(i)

    // === PRF接口 ===
    // 目标寄存器清空 valid（regWrite 且 rd≠0）
    io.out.PRF_bunle.alloc(i).valid := rename.ctrl.wbCtrl.regWrite && rename.logicRegs.rd =/= 0.U
    io.out.PRF_bunle.alloc(i).bits := rename.phyRd

    // 源寄存器读取请求
    io.out.PRF_bunle.readRS1(i).valid := rename.useRs1
    io.out.PRF_bunle.readRS1(i).bits := rename.phyRs1
    io.out.PRF_bunle.readRS2(i).valid := rename.useRs2
    io.out.PRF_bunle.readRS2(i).bits := rename.phyRs2

    // === 发射到 ALU ===
    val aluValid = rename.ctrl.aluCtrl.aluOpValid
    io.out.enqALU(i).valid := aluValid
    io.out.enqALU(i).bits := {
      val e = Wire(new AluIssueEntry)
      e.robIdx := rename.robIdx
      e.phyRd := rename.phyRd

      e.useRs1 := rename.useRs1
      e.phyRs1 := rename.phyRs1
      e.rs1Ready := io.in.reg.readRS1Ready(i)
      e.rs1data := io.in.reg.readRS1Data(i)

      e.useRs2 := rename.useRs2
      e.phyRs2 := rename.phyRs2
      e.rs2Ready := io.in.reg.readRS2Ready(i)
      e.rs2data := io.in.reg.readRS2Data(i)

      e.imm := rename.imm
      e.pc := rename.pc
      e.aluCtrl := rename.ctrl.aluCtrl
      e
    }

    // === 发射到 BR ===
    val brValid = rename.ctrl.brCtrl.isJal || rename.ctrl.brCtrl.isJalr || rename.ctrl.brCtrl.isBranch
    io.out.enqBR(i).valid := brValid
    io.out.enqBR(i).bits := {
      val b = Wire(new BrIssueEntry)
      b.robIdx := rename.robIdx
      b.phyRd := rename.phyRd

      b.useRs1 := rename.useRs1
      b.phyRs1 := rename.phyRs1
      b.rs1Ready := io.in.reg.readRS1Ready(i)
      b.rs1data := io.in.reg.readRS1Data(i)

      b.useRs2 := rename.useRs2
      b.phyRs2 := rename.phyRs2
      b.rs2Ready := io.in.reg.readRS2Ready(i)
      b.rs2data := io.in.reg.readRS2Data(i)

      b.func3 := rename.func3
      b.imm := rename.imm
      b.pc := rename.pc
      b.isBranch := rename.ctrl.brCtrl.isBranch
      b.isJal := rename.ctrl.brCtrl.isJal
      b.isJalr := rename.ctrl.brCtrl.isJalr
      b.PredictTarget := rename.jumpTarget
      b.tailPtr := rename.tailPtr
      b
    }

    // === 发射到 LSU ===
    val lsuValid = rename.ctrl.memCtrl.memRead || rename.ctrl.memCtrl.memWrite
    io.out.enqLSU(i).valid := lsuValid
    io.out.enqLSU(i).bits := {
      val l = Wire(new LsuIssueEntry)
      l.robIdx := rename.robIdx
      l.pc := rename.pc

      l.isLoad := rename.ctrl.memCtrl.memRead
      l.isStore := rename.ctrl.memCtrl.memWrite

      l.phyRd := rename.phyRd
      l.phyAddrBaseDest := rename.phyRs1
      l.AddrBaseData := io.in.reg.readRS1Data(i)
      l.addrReady := io.in.reg.readRS1Ready(i)

      l.phyStoreDataDest := rename.phyRs2
      l.StoreData := io.in.reg.readRS2Data(i)
      l.dataReady := io.in.reg.readRS2Ready(i)

      l.func3 := rename.func3
      l.imm := rename.imm

      l.isMov := false.B // 若你后续做伪指令处理，此处改为适配逻辑
      l
    }
  }
}
