package rv32isc

import chisel3._
import chisel3.util._

import bundles._
import config.Configs._
import config.OoOParams._

class RenameStage extends Module {
  val io = IO(new RenameStageIO)

  // === 实例化子模块 ===
  val rat = Module(new RAT)
  val freelist = Module(new FreeList)

  val rename_dispatch_regs = Seq.fill(FETCH_WIDTH)(Module(new RenameDispatchReg))

  // === 提交释放接口直通（由外部 COMMIT 阶段驱动） ===
  for(i <- 0 until MAX_COMMIT_WB){
    freelist.io.in.dealloc(i) := io.in.dealloc(i)
  }
  freelist.io.in.rollbackTail := io.in.rollbackTail
  // === 向 FreeList 提交申请信息 ===
  val needAllocVec = Wire(Vec(ISSUE_WIDTH, Bool()))
  for (i <- 0 until MAX_COMMIT_WB) {
    val idEntry = io.in.idVec(i)
    needAllocVec(i) := idEntry.ctrl.wbCtrl.regWrite && (idEntry.regs.rd =/= 0.U)
  }
  freelist.io.in.allocate := needAllocVec

  // === 准备 RAT 查询接口 ===
  for (i <- 0 until ISSUE_WIDTH) {
    val idEntry = io.in.idVec(i)
    rat.io.in.logicRs1(i) := idEntry.regs.rs1
    rat.io.in.logicRs2(i) := idEntry.regs.rs2
    rat.io.in.logicRd(i)  := idEntry.regs.rd
    rat.io.in.phyRd(i)    := freelist.io.out.phyRd(i)
    rat.io.in.wen(i)      := needAllocVec(i)
  }

  // === 主处理 ===
  for (i <- 0 until ISSUE_WIDTH) {
    val idEntry = io.in.idVec(i)
    val renamed = Wire(new RenameBundle)

    renamed.isRet     := io.in.idVec(i).isRet
    renamed.logicRegs := idEntry.regs
    renamed.useRs1    := idEntry.useRs1
    renamed.useRs2    := idEntry.useRs2
    renamed.ctrl      := idEntry.ctrl
    renamed.imm       := idEntry.imm
    renamed.func3     := idEntry.func3
    renamed.jumpTarget := idEntry.jumpTarget
    renamed.pc        := idEntry.pc
    //rat
    renamed.oldPhyRd  := rat.io.out.oldPhyRd(i)
    renamed.phyRs1    := rat.io.out.phyRs1(i)
    renamed.phyRs2    := rat.io.out.phyRs2(i)

    renamed.robIdx    := io.in.robIdx(i)
    renamed.isBubble := io.in.idVec(i).isBubble


    //给rob分配器的信号
    io.out.allocateValid(i) := !io.in.idVec(i).isBubble
    //freelist
    renamed.phyRd     := freelist.io.out.phyRd(i)
    renamed.tailPtr   := freelist.io.out.tailPtr

    //rob
    
    // === RAW bypass for rs1 ===
    rename_dispatch_regs(i).io.in.renameVec := renamed
    rename_dispatch_regs(i).io.in.stall := io.in.stall(i)
    rename_dispatch_regs(i).io.in.flush := io.in.flush(i) || io.in.idVec(i).isBubble

    io.out.renameVec(i) := rename_dispatch_regs(i).io.out.renameVec
  }
}

