package rv32isc
import chisel3._
import chisel3.util._

import bundles._
import config.Configs._
import config.InstructionConstants._

class Control extends Module {
  val io = IO(new ControlUnitIO)

  for (i <- 0 until FETCH_WIDTH) {
    val opcode = io.in.opcode(i)
    val funct3 = io.in.funct3(i)
    val funct7 = io.in.funct7(i)

    // === ALU 控制 ===
    val (aluOp, aluSrc, aluUnsigned,aluOpValid) = AluDecoder(opcode, funct3, funct7)
    io.out.aluCtrl(i).aluOp       := aluOp
    io.out.aluCtrl(i).aluSrc      := aluSrc
    io.out.aluCtrl(i).aluUnsigned := aluUnsigned
    io.out.aluCtrl(i).aluIsAuipc  := opcode === OP_AUIPC
    io.out.aluCtrl(i).aluIsLui    := opcode === OP_LUI
    io.out.aluCtrl(i).aluOpValid  := aluOpValid

    // === Mem 控制 ===
    val (memRead, memWrite) = MemDecoder(opcode)
    io.out.memCtrl(i).memRead  := memRead
    io.out.memCtrl(i).memWrite := memWrite

    // === 写回控制 ===
    io.out.wbCtrl(i).regWrite := RegWriteEnable(opcode,funct3)

    // === 分支跳转控制 ===
    io.out.brCtrl(i).isBranch := opcode === OP_BRANCH
    io.out.brCtrl(i).isJalr   := opcode === OP_JALR
    io.out.brCtrl(i).isJal   := opcode === OP_JAL
    io.out.brCtrl(i).isJump   := io.in.isJump(i)

    // === 源寄存器使用标记 ===
    io.out.useRs1(i) := opcode === OP_R || opcode === OP_I || opcode === OP_LOAD ||
                        opcode === OP_STORE || opcode === OP_BRANCH || opcode === OP_JALR

    io.out.useRs2(i) := opcode === OP_R || opcode === OP_STORE || opcode === OP_BRANCH
  }
}

object AluDecoder {
  def apply(opcode: UInt, funct3: UInt, funct7: UInt): (UInt, Bool, Bool, Bool) = {
    val op      = WireDefault(UInt(4.W),OP_NOP)
    val aluSrc  = WireDefault(false.B)
    val aluUnsigned = WireDefault(false.B)
    val aluOpValid = WireDefault(false.B)
//  	val isCSR = opcode === OP_SYSTEM && (
//  	  funct3 === F3_CSRRW  || funct3 === F3_CSRRS  || funct3 === F3_CSRRC ||
//  	  funct3 === F3_CSRRWI || funct3 === F3_CSRRSI || funct3 === F3_CSRRCI
//  	)

    // R-type 指令（寄存器+寄存器）
    when (opcode === OP_R) {
      aluOpValid := true.B
      aluSrc := false.B
      switch (funct3) {
        is (F3_ADD_SUB) {
          when (funct7 === F7_SUB) { op := OP_SUB }
          .otherwise               { op := OP_ADD }
        }
        is (F3_AND)  { op := OP_AND }
        is (F3_OR)   { op := OP_OR  }
        is (F3_XOR)  { op := OP_XOR }
        is (F3_SLL)  { op := OP_SLL }
        is (F3_SR) {
          when (funct7 === F7_SRA) { op := OP_SRA }
          .otherwise               { op := OP_SRL }
        }
        is (F3_SLT)  { op := OP_LT  }
        is (F3_SLTU) { 
                  op := OP_LT  
                  aluUnsigned := true.B } // 可分离为无符号
      }

    // I-type 指令（寄存器+立即数）
    } .elsewhen (opcode === OP_I) {
      aluOpValid := true.B
      aluSrc := true.B
      switch (funct3) {
        is (F3_ADD_SUB) { op := OP_ADD }
        is (F3_AND)     { op := OP_AND }
        is (F3_OR)      { op := OP_OR  }
        is (F3_XOR)     { op := OP_XOR }
        is (F3_SLL)     { op := OP_SLL }
        is (F3_SR) {
          when (funct7 === F7_SRA) { op := OP_SRA }
          .otherwise               { op := OP_SRL }
        }
        is (F3_SLT)     { op := OP_LT  }
        is (F3_SLTU)    { op := OP_LT
                          aluUnsigned := true.B 
                        } // 可分离为无符号
      }

    // Load / Store / JALR → 使用立即数进行地址计算
    } .elsewhen (opcode === OP_LOAD || opcode === OP_STORE || opcode === OP_JALR) {
      aluSrc := true.B
      op := OP_ADD

    // LUI / AUIPC → 使用立即数
    } .elsewhen (opcode === OP_LUI || opcode === OP_AUIPC) {
      aluOpValid := true.B
      aluSrc := true.B
      op := OP_ADD
    } .elsewhen (opcode === OP_BRANCH) {
      aluSrc := false.B  // 分支比较用 rs1 和 rs2

      switch (funct3) {
        is (F3_BEQ)  { op := OP_EQ  }
        is (F3_BNE)  { op := OP_NEQ }
        is (F3_BLT)  { op := OP_LT  }
        is (F3_BGE)  { op := OP_GE  }
        is (F3_BLTU) { 
                        op := OP_LT  
                        aluUnsigned := true.B
                      } // 无符号小于，可添加专用 OP if 你有
        is (F3_BGEU) { 
                        op := OP_GE  
                        aluUnsigned := true.B
                      } // 无符号大于等于
	  }
//  	}	.elsewhen (isCSR) {
//  			aluSrc := false.B
//  			op := OP_NOP
//  			aluUnsigned := false.B
	} .otherwise {
			op := OP_NOP
	}

    (op, aluSrc,aluUnsigned,aluOpValid)
  }
}

object MemDecoder {
  def apply(opcode: UInt): (Bool, Bool) = {
    val read  = opcode === OP_LOAD
    val write = opcode === OP_STORE
    (read, write)
  }
}

object RegWriteEnable {
  def apply(opcode: UInt, funct3: UInt): Bool = {
    val isCSR = opcode === OP_SYSTEM && (
                  funct3 === F3_CSRRW  || funct3 === F3_CSRRS  || funct3 === F3_CSRRC ||
                  funct3 === F3_CSRRWI || funct3 === F3_CSRRSI || funct3 === F3_CSRRCI
                )

    opcode === OP_R     || opcode === OP_I     || opcode === OP_LOAD ||
    opcode === OP_JAL   || opcode === OP_JALR  || opcode === OP_LUI  || opcode === OP_AUIPC ||
    isCSR
  }
}
