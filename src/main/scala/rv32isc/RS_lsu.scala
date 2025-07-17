package rv32isc
import chisel3._
import chisel3.util._

import bundles._
import config.Configs._
import config.OoOParams._

class LsuReservationStation extends Module {
  val io = IO(new LsuRSIO)

  val entries = Reg(Vec(RS_LS_SIZE, new LsuIssueEntry))
  val validVec = RegInit(VecInit(Seq.fill(RS_LS_SIZE)(false.B)))
  val tailPtr = RegInit(0.U(log2Ceil(RS_LS_SIZE).W))

  val enqValidVec = VecInit(io.in.enq.map(_.valid))
  val enqCount = PopCount(enqValidVec)
  val currentOccupied = PopCount(validVec)
  val spaceLeft = PopCount(~enqValidVec.asUInt)

  // === 生成每个入栈是否允许写入 ===
  val allowEnqVec = Wire(Vec(ISSUE_WIDTH, Bool()))
  val actualEnqVec = Wire(Vec(ISSUE_WIDTH, Bool()))

// === 满信号：不能满足全部请求 ===
  val notEnoughSpace = spaceLeft < enqCount
  io.out.full := notEnoughSpace

  // === 如果空间足够才允许写入 ===
  val doEnqueue = !notEnoughSpace

  val lastStoreIdx = RegInit(0.U(log2Ceil(RS_LS_SIZE).W))
  val idx = Vec(ISSUE_WIDTH,RegInit(0.U(log2Ceil(RS_LS_SIZE).W)))
  val isStoreVec = Wire(Vec(ISSUE_WIDTH, Bool()))

  for(i <- 0 until ISSUE_WIDTH) {
    when(doEnqueue && io.in.enq(i).valid) {
      idx(i) := (tailPtr + i.U)(log2Ceil(RS_LS_SIZE) - 1, 0)
    }
    isStoreVec(i) := io.in.enq(i).bits.isStore
  } 
  lastStoreIdx := Mux1H(isStoreVec.reverse, idx)


  for (i <- 0 until ISSUE_WIDTH) {
    io.in.enq(i).ready := doEnqueue

    when(doEnqueue && io.in.enq(i).valid) {
      val rawEntry = io.in.enq(i).bits
      val isLoad = rawEntry.isLoad
      val isStore = rawEntry.isStore

      val pseudoEntry = WireDefault(rawEntry)

      when(isLoad) {
        val st = entries(lastStoreIdx)
        val valid = validVec(lastStoreIdx)
        when(
          valid &&
            st.isStore &&
            st.addrReady &&
            st.dataReady &&
            st.phyAddrBase === rawEntry.phyAddrBase &&
            st.imm === rawEntry.imm
        ) {
          pseudoEntry.isPseudoMov := true.B
          pseudoEntry.pseudoSrc := st.phyStoreData
        }
      }

      entries(idx(i)) := pseudoEntry
      validVec(idx(i)) := true.B

    }
  }

  when(doEnqueue) {
    tailPtr := (tailPtr + enqCount)(log2Ceil(RS_LS_SIZE) - 1, 0)
  }

  for (i <- 0 until RS_LS_SIZE) {
  when(validVec(i)) {
    val entry = entries(i)

    val addrHit  = io.in.bypass.map(bp => bp.valid && bp.phyDest === entry.phyAddrBase).reduce(_ || _)
    val dataHit  = io.in.bypass.map(bp => bp.valid && bp.phyDest === entry.phyStoreData).reduce(_ || _)
    val pseudoHit = io.in.bypass.map(bp => bp.valid && bp.phyDest === entry.pseudoSrc).reduce(_ || _)

    // 地址寄存器前馈（load/store）
    when(!entry.addrReady && addrHit) {
      entries(i).addrReady := true.B
    }

    // 数据寄存器前馈（store）
    when(!entry.dataReady && !entry.isPseudoMov && dataHit) {
      entries(i).dataReady := true.B
    }

    // pseudo mov 伪指令专属 dataReady
    when(!entry.dataReady && entry.isPseudoMov && pseudoHit) {
      entries(i).dataReady := true.B
    }
  }
}

val headPtr = RegInit(0.U(log2Ceil(RS_LS_SIZE).W))
val FIRE_WIDTH = LSU_UNITS + MOV_UNITS

val fireVec   = Wire(Vec(FIRE_WIDTH, Bool()))
val entryVec  = Wire(Vec(FIRE_WIDTH, new LsuIssueEntry))
val entryIdxs = Wire(Vec(FIRE_WIDTH, UInt(log2Ceil(RS_LS_SIZE).W)))

for (i <- 0 until FIRE_WIDTH) {
  val idx = (headPtr + i.U)(log2Ceil(RS_LS_SIZE) - 1, 0)
  entryIdxs(i) := idx
  val entry = entries(idx)
  val valid = validVec(idx)
  val ready = Mux(entry.isPseudoMov, entry.dataReady, entry.addrReady && (entry.isStore || entry.dataReady))
  val targetBusy = Mux(entry.isPseudoMov, !io.out.pseudo.map(_.ready)(i % MOV_UNITS), !io.out.issue.map(_.ready)(i % LSU_UNITS))
  fireVec(i) := valid && ready && !targetBusy
  entryVec(i) := entry
}

// 找第一个不能发射的位置之前的都发射
val stopAt = Wire(UInt(log2Ceil(FIRE_WIDTH + 1).W))
stopAt := 0.U
for (i <- 0 until FIRE_WIDTH) {
  when(!fireVec(i) && stopAt === 0.U) {
    stopAt := i.U
  }
}
val fireCount = Mux(fireVec.reduce(_ && _), FIRE_WIDTH.U, stopAt)

// 发射并更新状态
for (i <- 0 until FIRE_WIDTH) {
  val fireThis = i.U < fireCount
  val entry = entryVec(i)
  val idx   = entryIdxs(i)

  when(fireThis) {
    when(entry.isPseudoMov) {
      val portIdx = i % MOV_UNITS
      io.out.pseudo(portIdx).valid := true.B
      io.out.pseudo(portIdx).bits := entry
    }.otherwise {
      val portIdx = i % LSU_UNITS
      io.out.issue(portIdx).valid := true.B
      io.out.issue(portIdx).bits := entry
    }
    validVec(idx) := false.B
  }.otherwise {
    when(entry.isPseudoMov) {
      val portIdx = i % MOV_UNITS
      io.out.pseudo(portIdx).valid := false.B
    }.otherwise {
      val portIdx = i % LSU_UNITS
      io.out.issue(portIdx).valid := false.B
    }
  }
}

  // 更新 headPtr
  headPtr := (headPtr + fireCount)(log2Ceil(RS_LS_SIZE) - 1, 0)

}
