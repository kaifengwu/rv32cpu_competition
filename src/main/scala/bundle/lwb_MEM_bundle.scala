package bundles
import chisel3._
import chisel3.util._
import config.Configs._
import config.InstructionConstants._
import config.OoOParams._

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

  // 写回旁路接口
  val writebackBus = Output(new WritebackBus)                // 添加专门的写回旁路总线

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