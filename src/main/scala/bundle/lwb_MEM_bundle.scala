package bundles
import chisel3._
import chisel3.util._
import config.Configs._
import config.InstructionConstants._
import config.OoOParams._

// 保留：现有Bundle（与TEST.scala兼容）
class MemoryUnitOutput extends Bundle {
  val aluResult = UInt(DATA_WIDTH.W)
  val readData = UInt(DATA_WIDTH.W)
}

class MEM_WB_Data extends Bundle {
  val memOut = new MemoryUnitOutput
  val rdAddr = UInt(REG_NUMS_LOG.W)
  val ctrl = new ControlSignals
  val pcPlus4 = UInt(ADDR_WIDTH.W)
  val isLoadInst = Bool()
  val isJumpInst = Bool()
}

class MEM_WB_IO extends Bundle {
  val in = new Bundle {
    val mem_data = Input(new MEM_WB_Data)
    val stall = Input(Bool())
    val flush = Input(Bool())
    val bubble = Input(Bool())
  }
  val out = Output(new MEM_WB_Data)
  val bubble = Output(Bool())
}

// 更新：支持StoreQueue的访存模块接口
class MemoryAccessInput extends Bundle {
  val addr = UInt(ADDR_WIDTH.W)     // 访存地址
  val ren = Bool()                  // 读使能
  val wen = Bool()                  // 写使能
  val mask = UInt(2.W)              // 访问掩码
  val wdata = UInt(DATA_WIDTH.W)    // 写数据
  val rdata = UInt(DATA_WIDTH.W)    // 从外部读取的原始数据
  val funct3 = UInt(3.W)            // 指令类型（用于数据处理）
  val fromStoreQueue = Bool()       // 标识是否来自StoreQueue的提交
  val robIdx = UInt(ROB_IDX_WIDTH.W) // ROB索引（用于StoreQueue提交）
}

class MemoryAccessOutput extends Bundle {
  val addr = UInt(ADDR_WIDTH.W)     // 访存地址
  val ren = Bool()                  // 读使能
  val wen = Bool()                  // 写使能
  val mask = UInt(2.W)              // 访问掩码
  val wdata = UInt(DATA_WIDTH.W)    // 处理后的写数据
  val rdata = UInt(DATA_WIDTH.W)    // 处理后的读数据
  val done = Bool()                 // 访存操作是否完成
}

class MemoryAccessIO extends Bundle {
  val in = Input(new MemoryAccessInput)
  val out = Output(new MemoryAccessOutput)
}

// 保留：现有MEM_IO（与TEST.scala兼容）
class MEM_IO extends Bundle {
  val in = new Bundle {
    val data = Input(Vec(FETCH_WIDTH, new EX_OUT))
    val stall = Input(Vec(FETCH_WIDTH, Bool()))
    val flush = Input(Vec(FETCH_WIDTH, Bool()))
    val bubble = Input(Vec(FETCH_WIDTH, Bool()))
  }

  // 保留：现有外设接口（与TEST.scala兼容）
  val in_perip = new Bundle {
    val rdata = Input(UInt(32.W))
  }

  val out_perip = new Bundle {
    val addr = Output(UInt(32.W))
    val wen = Output(Bool())
    val mask = Output(UInt(2.W))
    val wdata = Output(UInt(32.W))
  }

  val out = Output(Vec(FETCH_WIDTH, new MEM_WB_Data))
  val bubble = Output(Vec(FETCH_WIDTH, Bool()))
}

// 新增：LSU相关Bundle（为未来LSU设计准备）
class LSUAddressInput extends Bundle {
  val rs1_data = UInt(DATA_WIDTH.W)   // 基地址寄存器数据
  val imm = UInt(DATA_WIDTH.W)        // 立即数偏移
  val funct3 = UInt(3.W)              // 指令类型
  val isLoad = Bool()                 // 是否为加载指令
  val isStore = Bool()                // 是否为存储指令
}

class LSUAddressOutput extends Bundle {
  val addr = UInt(ADDR_WIDTH.W)       // 计算出的地址
  val valid = Bool()                  // 地址是否有效
  val accessMask = UInt(2.W)          // 访问掩码
  val canAccess = Bool()              // 是否可以访问（通过地址检查）
}

class LSUAddressIO extends Bundle {
  val in = Input(new LSUAddressInput)
  val out = Output(new LSUAddressOutput)
}

// 更新：添加伪指令处理支持的LSU接口
class LSUWithStoreQueueIO extends Bundle {
  // 从保留站接收指令
  val issue = Flipped(Decoupled(new LsuIssueEntry))

  // 旁路总线接口
  val bypassIn = Input(Vec(NUM_BYPASS_PORTS, new BypassBus))   // 接收前馈数据
  val bypassOut = Output(new BypassBus)                        // 输出地址计算结果

  // 结果输出接口
  val resultOut = ValidIO(new BypassBus)                     // 最终结果输出（普通指令）改为ValidIO
  val pseudoOut = ValidIO(new BypassBus)                     // 伪指令结果输出（伪mov指令）改为ValidIO

  // ROB写回接口
  val robWriteback = ValidIO(new RobWritebackEntry)          // 添加专门的ROB写回接口

  // store commit
  val rob_commit_store = Input(Vec(MAX_COMMIT_STORE, ValidIO(new RobCommitStoreEntry)))

  // 外设直连接口
  val perip_addr = Output(UInt(32.W))
  val perip_ren = Output(Bool())
  val perip_wen = Output(Bool())
  val perip_mask = Output(UInt(2.W))
  val perip_wdata = Output(UInt(32.W))
  val perip_rdata = Input(UInt(32.W))

  // 回滚信号
  val rollback = Input(Valid(UInt(ROB_IDX_WIDTH.W)))
  val tail = Input(UInt(ROB_IDX_WIDTH.W))

  val busy = Output(Bool())                                    // LSU忙信号
}