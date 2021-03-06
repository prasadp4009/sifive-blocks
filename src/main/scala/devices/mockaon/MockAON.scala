// See LICENSE for license details.
package sifive.blocks.devices.mockaon

import Chisel._
import config._
import regmapper._
import uncore.tilelink2._
import rocketchip.PeripheryBusConfig

import sifive.blocks.util.GenericTimer

case class MockAONConfig(
  address: BigInt = BigInt(0x10000000),
  nBackupRegs: Int = 16) {
  def size: Int = 0x1000
  def regBytes: Int = 4
  def wdogOffset: Int = 0
  def rtcOffset: Int = 0x40
  def backupRegOffset: Int = 0x80
  def pmuOffset: Int = 0x100
}

trait HasMockAONParameters {
  implicit val p: Parameters
  val params: MockAONConfig
  val c = params
}

class MockAONPMUIO extends Bundle {
  val vddpaden = Bool(OUTPUT)
  val dwakeup = Bool(INPUT)
}

class MockAONMOffRstIO extends Bundle {
  val hfclkrst = Bool(OUTPUT)
  val corerst = Bool(OUTPUT)
}

trait MockAONBundle extends Bundle with HasMockAONParameters {

  // Output of the Power Management Sequencer
  val moff = new MockAONMOffRstIO ()

  // This goes out to wrapper
  // to be combined to create aon_rst.
  val wdog_rst = Bool(OUTPUT)

  // This goes out to wrapper
  // and comes back as our clk
  val lfclk = Clock(OUTPUT)

  val pmu = new MockAONPMUIO

  val lfextclk = Clock(INPUT)

  val resetCauses = new ResetCauses().asInput
}

trait MockAONModule extends Module with HasRegMap with HasMockAONParameters {
  val io: MockAONBundle

  // the expectation here is that Chisel's implicit reset is aonrst,
  // which is asynchronous, so don't use synchronous-reset registers.

  val rtc = Module(new RTC)

  val pmu = Module(new PMU(new DevKitPMUConfig))
  io.moff <> pmu.io.control
  io.pmu.vddpaden := pmu.io.control.vddpaden
  pmu.io.wakeup.dwakeup := io.pmu.dwakeup
  pmu.io.wakeup.awakeup := Bool(false)
  pmu.io.wakeup.rtc := rtc.io.ip(0)
  pmu.io.resetCauses := io.resetCauses
  val pmuRegMap = {
    val regs = pmu.io.regs.wakeupProgram ++ pmu.io.regs.sleepProgram ++
      Seq(pmu.io.regs.ie, pmu.io.regs.cause, pmu.io.regs.sleep, pmu.io.regs.key)
    for ((r, i) <- regs.zipWithIndex)
      yield (c.pmuOffset + c.regBytes*i) -> Seq(r.toRegField())
  }
  interrupts(1) := rtc.io.ip(0)

  val wdog = Module(new WatchdogTimer)
  io.wdog_rst := wdog.io.rst
  wdog.io.corerst := pmu.io.control.corerst
  interrupts(0) := wdog.io.ip(0)

  // If there are multiple lfclks to choose from, we can mux them here.
  io.lfclk := io.lfextclk

  val backupRegs = Seq.fill(c.nBackupRegs)(Reg(UInt(width = c.regBytes * 8)))
  val backupRegMap =
    for ((reg, i) <- backupRegs.zipWithIndex)
      yield (c.backupRegOffset + c.regBytes*i) -> Seq(RegField(reg.getWidth, RegReadFn(reg), RegWriteFn(reg)))

  regmap((backupRegMap ++
    GenericTimer.timerRegMap(wdog, c.wdogOffset, c.regBytes) ++
    GenericTimer.timerRegMap(rtc, c.rtcOffset, c.regBytes) ++
    pmuRegMap):_*)

}

class MockAON(c: MockAONConfig)(implicit p: Parameters)
  extends TLRegisterRouter(c.address, interrupts = 2, size = c.size, beatBytes = p(PeripheryBusConfig).beatBytes, concurrency = 1)(
  new TLRegBundle(c, _)    with MockAONBundle)(
  new TLRegModule(c, _, _) with MockAONModule)
