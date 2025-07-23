// 这个单元是汇总控制信号并且输出给各个模块的 
package rv32isc

import chisel3._
import chisel3.util._
import bundles._

import config.Configs._
import config.OoOParams._

class Control_CPU_UNIT extends Module{
  val io = IO(new ControlBundleIO)
  //stall 信号处理
  val stall_by_rs = io.in.rs.alu_rs_full || io.in.rs.br_rs_full || io.in.rs.lsu_rs_full
  val stall_by_rob_full = io.in.robFull


  io.out.stall.stall_IF := stall_by_rs || stall_by_rob_full
  io.out.stall.stall_ID := stall_by_rs || stall_by_rob_full
  io.out.stall.stall_RE := stall_by_rs 


  val flush_by_ret_waiting = io.in.waitRet
  val flush_by_return = io.in.predictedRet.valid
  val flush_by_rollback = io.in.rollBack.valid

  //flush 信号处理
  io.out.flush.flush_IF := flush_by_ret_waiting || flush_by_rollback || flush_by_return
  io.out.flush.flush_ID := flush_by_rollback
  io.out.flush.flush_RE := flush_by_rollback  || stall_by_rob_full

  //分支预测器更新
  val update_valid = io.in.br.bits.isBranch || io.in.br.bits.isJal || io.in.br.bits.isJalr || !io.in.br.bits.isRet
  io.out.update.bits.pc := io.in.br.bits.pc
  io.out.update.bits.target := io.in.br.bits.predictTarget
  io.out.update.valid := update_valid

  //PC寄存器重更新
  io.out.redirect.valid := io.in.br.valid && io.in.br.bits.wrongPredict //错误预测
  io.out.redirect.bits := io.in.br.bits.redirectTarget
  
  io.out.retTarget := io.in.predictedRet
  //RAS return地址更新
  io.out.retTarget := io.in.predictedRet
  io.out.retcommit.valid := io.in.br.valid
  io.out.retcommit.bits := io.in.br.bits.isRet //bu返回给RAS 解除retwaiting状态


  //回滚
  val rollBackValid = io.in.br.bits.wrongPredict && io.in.br.valid
  //rob编号回滚
  io.out.rollBackIdx.valid := rollBackValid
  io.out.rollBackIdx.bits := io.in.br.bits.robIdx

  //rob双编号回滚接口
  io.out.rollback.valid := rollBackValid
  io.out.rollback.bits.rollbackIdx := io.in.br.bits.robIdx
  io.out.rollback.bits.tailIdx := io.in.tailRob
  //pc回滚
  io.out.rollbackPc.valid := rollBackValid
  io.out.rollbackPc.bits := io.in.br.bits.pc

  //物理寄存器tailPtr回滚
  io.out.rollbackTail.valid := rollBackValid
  io.out.rollbackTail.bits := io.in.br.bits.tailPtr
}
