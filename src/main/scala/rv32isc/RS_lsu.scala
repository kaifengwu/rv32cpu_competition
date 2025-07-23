package rv32isc

import chisel3._
import chisel3.util._

import bundles._
import config.Configs._
import config.OoOParams._
import LsuRSUtils._

class LsuRS extends Module {
  val io = IO(new LsuRSIO)
// === LSU 保留站结构 ===
  val entries = RegInit(VecInit(Seq.fill(RS_LS_SIZE)(0.U.asTypeOf(Valid(new LsuIssueEntry)))))
  val tailPtr = RegInit(0.U(log2Ceil(RS_LS_SIZE).W))
  val headPtr = RegInit(0.U(log2Ceil(RS_LS_SIZE).W))

// === 剩余空间判断 ===
  val spaceLeft = PopCount(entries.map(!_.valid))
  io.out.freeEntryCount := Mux(spaceLeft > ISSUE_WIDTH.U, ISSUE_WIDTH.U, spaceLeft)

// === 入队指令数量 ===
  val enqVec = io.in.enq
  val enqCount = PopCount(enqVec.map(_.valid))

// === 入队执行 ===
  val inputStall = enqCount > spaceLeft
  val realEnq = Wire(Vec(ISSUE_WIDTH, Bool()))

  for (i <- 0 until ISSUE_WIDTH) {
    when(!inputStall && !io.in.rollback.valid){
      realEnq(i) := enqVec(i).valid && (i.U < spaceLeft)
    }
  }

// === 封装“当前周期已分配但尚未写入 entries 的 Store 指令”，供 MOV 检查用 ===
  val currentStores = Wire(Vec(ISSUE_WIDTH, Valid(new LsuIssueEntry)))
  for (i <- 0 until ISSUE_WIDTH) {
    val e = Wire(Valid(new LsuIssueEntry))
    e.valid := realEnq(i) && enqVec(i).bits.isStore
    e.bits  := enqVec(i).bits
    currentStores(i) := e
  }

// === 真正入队逻辑 ===
  for (i <- 0 until ISSUE_WIDTH) {
    when(realEnq(i) && !io.in.rollback.valid) {
      val enq = enqVec(i)
      val entry = Wire(new LsuIssueEntry)
      entry := enq.bits

      // === MOV 检查逻辑 ===
      val checkVec = Wire(Vec(RS_LS_SIZE + ISSUE_WIDTH, Bool()))  // 所有之前 valid 的 store + 本周期 store

      for (j <- 0 until RS_LS_SIZE) {
        val idx = (headPtr + j.U)(log2Ceil(RS_LS_SIZE) - 1, 0)
        val store = entries(idx)
        checkVec(j) := store.valid &&
          store.bits.isStore &&
          store.bits.func3 === entry.func3 &&
          store.bits.phyAddrBaseDest === entry.phyAddrBaseDest &&
          store.bits.imm === entry.imm
      }

      for (j <- 0 until ISSUE_WIDTH) {
        val store = currentStores(j)
        checkVec(RS_LS_SIZE + j) := store.valid &&
          store.bits.func3 === entry.func3 &&
          store.bits.phyAddrBaseDest === entry.phyAddrBaseDest &&
          store.bits.imm === entry.imm
      }


      val matchIdxReversed = PriorityEncoder(checkVec.reverse)
      val matchIdx = (checkVec.length - 1).U - matchIdxReversed
      val matched = checkVec.asUInt.orR

      when(entry.isLoad && matched) {
        val storeEntry = WireDefault(0.U.asTypeOf(new LsuIssueEntry))

        // 来自 entries
        when(matchIdx < RS_LS_SIZE.U) {
          val realIdx = (headPtr + matchIdx)(log2Ceil(RS_LS_SIZE) - 1, 0)
          storeEntry := entries(realIdx).bits
        } .otherwise {
        // 来自本周期入队
          val currentIdx = matchIdx - RS_LS_SIZE.U
          storeEntry := currentStores(currentIdx).bits
        }

        entry.isMov := true.B
        entry.phyStoreDataDest := storeEntry.phyStoreDataDest
        entry.StoreData := storeEntry.StoreData
        entry.dataReady := storeEntry.dataReady
      }

      // === 写入队列 ===
      val issueIdx = PopCount(realEnq.slice(0,i + 1))
      val idx = (tailPtr + issueIdx)(log2Ceil(RS_LS_SIZE) - 1, 0)
      entries(idx).valid := true.B
      entries(idx).bits := entry
    }
  }


  val addrBypassHit = WireInit(VecInit(Seq.fill(RS_LS_SIZE)(false.B)))
  val dataBypassHit = WireInit(VecInit(Seq.fill(RS_LS_SIZE)(false.B)))

  // === bypass 更新寄存器就绪状态（不论是否发射） ===
  for (i <- 0 until RS_LS_SIZE) {
    val entry = entries(i)
    when(entry.valid && !io.in.rollback.valid) {
      for (bp <- io.in.bypass) {
        // 地址基址寄存器前馈
        when(!entry.bits.addrReady && bp.valid && bp.reg.phyDest === entry.bits.phyAddrBaseDest) {
          entry.bits.AddrBaseData := bp.data
          entry.bits.addrReady := true.B
          addrBypassHit(i) := true.B
        }

        // Store 数据寄存器前馈（只有 Store 或 MOV 指令会用）
        when(!entry.bits.dataReady && bp.valid && bp.reg.phyDest === entry.bits.phyStoreDataDest) {
          entry.bits.StoreData := bp.data
          entry.bits.dataReady := true.B
          dataBypassHit(i) := true.B
        }
      }
    }
  }

  // === 发射控制 ===
  val MAX_LSU_ISSUE = LSU_UNITS + MOV_UNITS
  val issueLsu = Wire(Vec(LSU_UNITS, Decoupled(new LsuIssueEntry)))
  val issuePseudo = Wire(Vec(MOV_UNITS, Decoupled(new LsuIssueEntry)))
  val fireMask = Wire(Vec(MAX_LSU_ISSUE, Bool()))  // 标记已发射
  val canFire  = Wire(Vec(MAX_LSU_ISSUE, Bool()))  // 标记能发射
  val isPseudo = Wire(Vec(MAX_LSU_ISSUE, Bool()))  // 标记是否为伪指令方式发射

  
  fireMask.foreach(_ := false.B)
  canFire.foreach(_ := false.B)
  isPseudo.foreach(_ := false.B)


  val pseudoIssued = WireInit(VecInit(Seq.fill(MOV_UNITS)(false.B)))
  val lsuIssued    = WireInit(VecInit(Seq.fill(LSU_UNITS)(false.B)))
  
  // === 从 headPtr 顺序判断 ===
  for (i <- 0 until MAX_LSU_ISSUE) {
    val idx = (headPtr + i.U)(log2Ceil(RS_LS_SIZE) - 1, 0)
    val entry = entries(idx)
  
    when(entry.valid && !io.in.rollback.valid) {
      val isLoad = entry.bits.isLoad
      val isStore = entry.bits.isStore
      val isMov = entry.bits.isMov
      val addrReady = entry.bits.addrReady || addrBypassHit(idx)
      val dataReady = entry.bits.dataReady || dataBypassHit(idx)
  
      val loadFire = isLoad && addrReady && (!isMov || dataReady)
      val storeFire = isStore && addrReady && dataReady
      val movFire = isMov && dataReady

      val thisCanFire = loadFire || storeFire || movFire
      canFire(i) := thisCanFire
  
      // 若之前有不满足的，就不再考虑发射
      val prevCanFireMask = if (i == 0) true.B else canFire.slice(0, i).reduce(_ && _)
      val mayFire = thisCanFire && prevCanFireMask
  
      // === 优先发到 pseudo ===
      val pseudoSlot = PriorityEncoder(pseudoIssued.map(!_))
      val pseudoAvailable = !pseudoIssued.reduce(_ && _)

      val addrBypass = WireDefault(false.B)
      val dataBypass = WireDefault(false.B)

      val addrValue  = WireDefault(entry.bits.AddrBaseData)
      val dataValue  = WireDefault(entry.bits.StoreData)

      for (j <- 0 until NUM_BYPASS_PORTS) {
        val bp = io.in.bypass(j)
        when(bp.valid && !entry.bits.addrReady && bp.reg.phyDest=== entry.bits.phyAddrBaseDest ) {
          addrBypass := true.B
          addrValue := bp.data
        }
        when(bp.valid && !entry.bits.dataReady && bp.reg.phyDest === entry.bits.phyStoreDataDest) {
          dataBypass := true.B
          dataValue := bp.data
        }
      }



      when(mayFire && isMov && pseudoAvailable) {
        val slot = pseudoSlot
        issuePseudo(slot).valid := true.B
        issuePseudo(slot).bits := entry.bits
        issuePseudo(slot).bits.AddrBaseData := addrValue
        issuePseudo(slot).bits.StoreData := dataValue 

        fireMask(i) := true.B
        pseudoIssued(slot) := true.B
        isPseudo(i) := true.B
      }.elsewhen(mayFire && (!isMov || !pseudoAvailable)) {
        val lsuSlot = PriorityEncoder(lsuIssued.map(!_))
        val lsuAvailable = !lsuIssued.reduce(_ && _)
        when(lsuAvailable) {
          val slot = lsuSlot
          issueLsu(slot).valid := true.B
          issueLsu(slot).bits := entry.bits
          issueLsu(slot).bits.AddrBaseData := addrValue
          issueLsu(slot).bits.StoreData := dataValue 
          fireMask(i) := true.B
          lsuIssued(slot) := true.B
        }
      }
    }
  }

// === 发射端口连接 ===
  for (i <- 0 until LSU_UNITS) {
    io.out.issue(i).valid := issueLsu(i).valid && io.in.rollback.valid === false.B
    io.out.issue(i).bits := issueLsu(i).bits
  }

  for (i <- 0 until MOV_UNITS) {
    io.out.pseudo(i).valid := issuePseudo(i).valid && io.in.rollback.valid === false.B
    io.out.pseudo(i).bits := issuePseudo(i).bits
  }

  when(!io.in.rollback.valid) {
    for(i <- 0 until MAX_LSU_ISSUE) {
      when(canFire(i)) {
        entries((headPtr + i.U)(log2Ceil(RS_LS_SIZE) - 1, 0)).valid := false.B
      }
    }
  }



// === 更新 headPtr ===
  val commitCount = PopCount(fireMask)
  when(commitCount =/= 0.U && !io.in.rollback.valid) {
    headPtr := headPtr + commitCount
  }

// === 回滚处理 ===
  when(io.in.rollback.valid) {
    val rollbackIdx = io.in.rollback.bits.rollbackIdx
    val tailIdx = io.in.rollback.bits.tailIdx
  
    val clearVec = WireDefault(VecInit(Seq.fill(RS_LS_SIZE)(false.B)))

    for (i <- 0 until RS_LS_SIZE) {
      val entry = entries(i)
      when(entry.valid && isAfterRollback(entry.bits.robIdx, io.in.rollback.bits)) {
        clearVec(i) := true.B
        entry.valid := false.B
      }
    }
    val hasRollback = clearVec.asUInt.orR
    val rollbackCount = PopCount(clearVec)

  // 更新 tail 指针（确保栈尾指向最后一个保留指令之后）;
    when(hasRollback) {
      tailPtr := (tailIdx - rollbackCount)(log2Ceil(RS_LS_SIZE) - 1, 0)
    }

  }.elsewhen(enqCount =/= 0.U && !io.in.rollback.valid) {
    tailPtr := (tailPtr + PopCount(realEnq.asUInt))(log2Ceil(RS_LS_SIZE) - 1,0)
  }
}




object LsuRSUtils {
  def isAfterRollback(robIdx: UInt, rollback: RsRollbackEntry): Bool = {
    val tail = rollback.tailIdx
    val target = rollback.rollbackIdx
    Mux(tail >= target,
      robIdx > target && robIdx < tail,
      robIdx > target || robIdx < tail
    )
  }
}
