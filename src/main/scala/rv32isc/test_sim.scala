package rv32isc
import chisel3._
import chisel3.util._

import bundles._
import config.Configs._
import config.InstructionConstants._
import Chisel.OUTPUT
import javax.xml.transform.OutputKeys
import config.OoOParams._



class SIM_CPU extends Module{
    val io = IO(new Bundle{
      val in = new Bundle{
          val flush = Input(Vec(FETCH_WIDTH,Bool()))
          val stall = Input(Vec(FETCH_WIDTH,Bool()))
      }
      val out = new Bundle{
//          val mispredict = Output(Bool())
//          val predictTaken = Output(Bool())
//           val pc = Output(Vec(FETCH_WIDTH,UInt(ADDR_WIDTH.W)))
//          val have_insts = Output(Vec(FETCH_WIDTH,Bool()))
//          val mcycle = Output(UInt((2*DATA_WIDTH).W))

//          val insts = Output(Vec(FETCH_WIDTH,UInt(DATA_WIDTH.W)))

//          val display_addr = Output(Vec(FETCH_WIDTH,UInt(ADDR_WIDTH.W)))
//          val display = Output(Vec(FETCH_WIDTH,UInt(8.W)))

        //调试用代码
        val rdAddr = Output(Vec(FETCH_WIDTH,UInt(REG_NUMS_LOG.W)))
        val rdData = Output(Vec(FETCH_WIDTH,UInt(DATA_WIDTH.W)))
        val rdEn = Output(Vec(FETCH_WIDTH,Bool()))
        val rdPC = Output(Vec(FETCH_WIDTH,UInt(ADDR_WIDTH.W)))
        
//          val isStore = Output(Vec(FETCH_WIDTH,Bool()))
//          val storePC = Output(Vec(FETCH_WIDTH,UInt(ADDR_WIDTH.W)))
//          val storeAddr = Output(Vec(FETCH_WIDTH,UInt(DATA_WIDTH.W))) 
//          val storeData = Output(Vec(FETCH_WIDTH,UInt(DATA_WIDTH.W))) 
      }
    })
 
    val IF = Module(new IFStage)
    val ID = Module(new ID)
    val Ras = Module(new RAS)


    val Rename = Module(new RenameStage)

    val RobIndex = Module(new RobIndexAllocator)
    val PRF = Module(new PRF)
    val Dispatch = Module(new Dispatch)


    val RS_ALU = Module(new AluRS)
    val RS_BR = Module(new BrRS)
    val RS_LSU = Module(new LsuRS)

    val ALU = Seq.fill(ALU_UNITS)(Module(new ALU_Top))
    val BR  = Seq.fill(BR_UNITS)(Module(new BU_Top))
    val LSU = Seq.fill(LSU_UNITS)(Module(new LSU_Top))
    val MOV = Seq.fill(MOV_UNITS)(Module(new MovU_Top))

    val ROB = Module(new ROB)

    for(i <- 0 until FETCH_WIDTH){ 
      ID.io.in.ifVec(i) := IF.io.out.toDecode(i)
      Ras.io.in.checkpoint(i) := ID.io.out.ToRAS.checkpoint(i) //输入保留快照的使能
      Ras.io.in.pushReqVec(i) := ID.io.out.ToRAS.pushReqVec(i) //压栈请求

      Rename.io.in.idVec(i) := ID.io.out.idVec(i)
      Rename.io.in.robIdx(i) := RobIndex.io.out.allocateIdx(i)
      RobIndex.io.in.allocateValid(i) := Rename.io.out.allocateValid(i)

      Dispatch.io.in.renameVec(i) := Rename.io.out.renameVec(i)
      PRF.io.in.alloc(i) := Dispatch.io.out.PRF_bunle.alloc(i)
      PRF.io.in.readRS1(i) := Dispatch.io.out.PRF_bunle.readRS1(i)
      PRF.io.in.readRS2(i) := Dispatch.io.out.PRF_bunle.readRS2(i)

      Dispatch.io.in.reg.readRS1Data(i) := PRF.io.out.readRS1Data(i)
      Dispatch.io.in.reg.readRS2Data(i) := PRF.io.out.readRS2Data(i)

      Dispatch.io.in.reg.readRS1Ready(i) := PRF.io.out.readRS1Ready(i)
      Dispatch.io.in.reg.readRS2Ready(i) := PRF.io.out.readRS2Ready(i)

    }
    Ras.io.in.popValid := ID.io.out.ToRAS.popValid //出栈请求,遇到 ret 指令

    for(i <- 0 until ISSUE_WIDTH){ 
      RS_ALU.io.in.enq(i) := Dispatch.io.out.enqALU(i)
      RS_BR.io.in.enq(i) := Dispatch.io.out.enqBR(i)
      RS_LSU.io.in.enq(i) := Dispatch.io.out.enqLSU(i)
      ROB.io.in.allocate(i) := Dispatch.io.out.allocate(i)
    }
    //保留站发射执行级
    for(i <- 0 until ALU_UNITS){ 
      ALU(i).io.in <> RS_ALU.io.out.issue(i)
    }
    for(i <- 0 until BR_UNITS){ 
      BR(i).io.in <> RS_BR.io.out.issue(i)
    }
    for(i <- 0 until MOV_UNITS){ 
      MOV(i).io.issue <> RS_LSU.io.out.pseudo(i)
    }
    for(i <- 0 until LSU_UNITS){ 
      LSU(i).io.issue <> RS_LSU.io.out.issue(i)
    }
    //Control_Unit 的输入输出口，Control是总控单元，控制各个单元的阻塞回滚
    val Control_Unit = Module(new Control_CPU_UNIT)

    val Bypass_to_RS = Module(new BypassUnit)
    val Bypass_to_PRF = Module(new BypassUnit)

    Control_Unit.io.in.stall := io.in.stall.asUInt.orR
    Control_Unit.io.in.flush := io.in.flush.asUInt.orR 

    for(i <- 0 until ALU_UNITS){ 
      Bypass_to_RS.io.in(i) := ALU(i).io.bypassBus
      Bypass_to_PRF.io.in(i) := ALU(i).io.writebackBus
      ROB.io.in.writeback(i).valid := ALU(i).io.writebackBus.valid
      ROB.io.in.writeback(i).bits.robIdx := ALU(i).io.writebackBus.reg.robIdx
      ALU(i).io.rollback := Control_Unit.io.out.rollback
      ALU(i).io.stall := io.in.stall.asUInt.orR
      ALU(i).io.flush := io.in.flush.asUInt.orR
    }
    for(i <- 0 until MOV_UNITS){ 
      Bypass_to_RS.io.in(i + ALU_UNITS) := MOV(i).io.resultOut
      Bypass_to_PRF.io.in(i + ALU_UNITS) := MOV(i).io.writebackBus
      ROB.io.in.writeback(i + ALU_UNITS).valid := MOV(i).io.writebackBus.valid
      ROB.io.in.writeback(i + ALU_UNITS).bits.robIdx := MOV(i).io.writebackBus.reg.robIdx
      MOV(i).io.rollback := Control_Unit.io.out.rollback
      MOV(i).io.stall := io.in.stall.asUInt.orR
      MOV(i).io.flush := io.in.flush.asUInt.orR
    }
    for(i <- 0 until LSU_UNITS){ 
      Bypass_to_RS.io.in(i + ALU_UNITS + MOV_UNITS) := LSU(i).io.bypassOut
      Bypass_to_PRF.io.in(i + ALU_UNITS + MOV_UNITS) := LSU(i).io.writebackBus
      ROB.io.in.writeback(i + ALU_UNITS + MOV_UNITS).valid := LSU(i).io.writebackBus.valid
      ROB.io.in.writeback(i + ALU_UNITS + MOV_UNITS).bits.robIdx := LSU(i).io.writebackBus.reg.robIdx

      LSU(i).io.rollback := Control_Unit.io.out.rollback
      LSU(i).io.commit_store := ROB.io.out.commit_store(i)
    }
    for(i <- 0 until MAX_COMMIT_WB){ 
        Rename.io.in.dealloc(i).valid := ROB.io.out.commit_wb(i).valid && ROB.io.out.commit_wb(i).bits.hasRd 
        Rename.io.in.dealloc(i).bits := ROB.io.out.commit_wb(i).bits.rd
    }

    RobIndex.io.in.commitCount := ROB.io.out.commitCount
    RobIndex.io.in.stall := Control_Unit.io.out.stall.stall_RE

    for(i <- 0 until BR_UNITS){ 
      Bypass_to_RS.io.in(i + ALU_UNITS + LSU_UNITS + MOV_UNITS) := BR(i).io.bypassBus
      Bypass_to_PRF.io.in(i + ALU_UNITS + LSU_UNITS + MOV_UNITS) := BR(i).io.writebackBus
      ROB.io.in.writeback(i + ALU_UNITS + LSU_UNITS + MOV_UNITS).valid := BR(i).io.writebackBus.valid
      ROB.io.in.writeback(i + ALU_UNITS + LSU_UNITS + MOV_UNITS).bits.robIdx := BR(i).io.writebackBus.reg.robIdx
      BR(i).io.rollback := Control_Unit.io.out.rollback

      BR(i).io.stall := io.in.stall.asUInt.orR
      BR(i).io.flush := io.in.flush.asUInt.orR
    }


    for(i <- 0 until NUM_BYPASS_PORTS){ 
        RS_ALU.io.in.bypass(i) := Bypass_to_RS.io.out(i)
        RS_BR.io.in.bypass(i) := Bypass_to_RS.io.out(i)
        RS_LSU.io.in.bypass(i) := Bypass_to_RS.io.out(i)
        PRF.io.in.write(i) := Bypass_to_PRF.io.out(i)
    }


    //控制器输入
    for(i <- 0 until BR_UNITS){ 
      Control_Unit.io.in.br.valid := BR(i).io.jumpBus.valid
      Control_Unit.io.in.br.bits.isBranch :=  BR(i).io.jumpBus.bits.isBranch
      Control_Unit.io.in.br.bits.isJal :=  BR(i).io.jumpBus.bits.isJal
      Control_Unit.io.in.br.bits.isJalr :=  BR(i).io.jumpBus.bits.isJalr
      Control_Unit.io.in.br.bits.isJump := BR(i).io.jumpBus.bits.isTaken
      
      Control_Unit.io.in.br.bits.isRet :=  BR(i).io.jumpBus.bits.isReturnOut
      Control_Unit.io.in.br.bits.pc :=  BR(i).io.jumpBus.bits.pc
      Control_Unit.io.in.br.bits.predictTarget :=  BR(i).io.jumpBus.bits.targetPredictionCorrect
      Control_Unit.io.in.br.bits.redirectTarget :=  BR(i).io.jumpBus.bits.branch_actual_target
      Control_Unit.io.in.br.bits.wrongPredict :=  BR(i).io.jumpBus.bits.mispredict
      Control_Unit.io.in.br.bits.robIdx :=  BR(i).io.jumpBus.bits.robIdx
      Control_Unit.io.in.br.bits.tailPtr :=  BR(i).io.jumpBus.bits.tailPtr
      Control_Unit.io.in.rollBack.valid := BR(i).io.jumpBus.bits.mispredict
      Control_Unit.io.in.rollBack.bits.robPc := BR(i).io.jumpBus.bits.pc
    }

    Control_Unit.io.in.predictedRet := Ras.io.out.predictedRet
    Control_Unit.io.in.robFull := RobIndex.io.out.isFull
    Control_Unit.io.in.tailRob := ROB.io.out.tail
    Control_Unit.io.in.rollBack.bits.rollbackIdx := BR(0).io.jumpBus.bits.robIdx
    Control_Unit.io.in.waitRet := Ras.io.out.retstall
    Control_Unit.io.in.rs.alu_rs_full := RS_ALU.io.out.isFull
    Control_Unit.io.in.rs.lsu_rs_full:= RS_LSU.io.out.isFull
    Control_Unit.io.in.rs.br_rs_full := RS_BR.io.out.isFull
    //控制器输出
    IF.io.in.redirect := Control_Unit.io.out.redirect
    IF.io.in.retTarget := Control_Unit.io.out.retTarget
    IF.io.in.update := Control_Unit.io.out.update
    IF.io.in.stall := Control_Unit.io.out.stall.stall_IF
    IF.io.in.flush := Control_Unit.io.out.flush.flush_IF

    ID.io.in.retTarget := Control_Unit.io.out.redirect//跳转地址写入流水线，供BR判断
    ID.io.in.stall := Control_Unit.io.out.stall.stall_ID
    ID.io.in.flush := Control_Unit.io.out.flush.flush_ID

    Ras.io.in.retcommit := Control_Unit.io.out.retcommit
    Ras.io.in.rollback := Control_Unit.io.out.rollbackPc
    Ras.io.in.commit := Control_Unit.io.out.commit_to_Ras
    Ras.io.in.stall := Control_Unit.io.out.stall.stall_ID
    Ras.io.in.retcommit := Control_Unit.io.out.retcommit

    RS_ALU.io.in.rollback := Control_Unit.io.out.rollback
    RS_BR.io.in.rollback := Control_Unit.io.out.rollback
    RS_LSU.io.in.rollback := Control_Unit.io.out.rollback

    ROB.io.in.rollback := Control_Unit.io.out.rollBackIdx


    RobIndex.io.in.rollback := Control_Unit.io.out.rollBackIdx

    Rename.io.in.rollbackPc := Control_Unit.io.out.rollbackPc
    Rename.io.in.rollbackTail := Control_Unit.io.out.rollbackTail
    Rename.io.in.stall := Control_Unit.io.out.stall.stall_RE
    Rename.io.in.flush := Control_Unit.io.out.flush.flush_RE

    Dispatch.io.in.stall_by_rs := Control_Unit.io.out.stall.stall_RE

    for(i <- 0 until MAX_COMMIT_WB){ 
      PRF.io.in.commit_wb(i).bits := ROB.io.out.commit_wb(i).bits.rd
      PRF.io.in.commit_wb(i).valid := ROB.io.out.commit_wb(i).valid && ROB.io.out.commit_wb(i).bits.hasRd
      io.out.rdAddr(i) := ROB.io.out.commit_wb(i).bits.lrd
      io.out.rdEn(i) := ROB.io.out.commit_wb(i).bits.hasRd && ROB.io.out.commit_wb(i).valid
      io.out.rdData(i) := PRF.io.out.commit_out_data(i).bits
      io.out.rdPC(i) := ROB.io.out.commit_wb(i).bits.pc
    }
}
