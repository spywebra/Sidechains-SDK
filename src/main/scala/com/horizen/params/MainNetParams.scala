package com.horizen.params
import java.math.BigInteger

object MainNetParams extends NetworkParams {
  override val EquihashN: Int = 200
  override val EquihashK: Int = 9
  val EquihashVarIntLength: Int = 3
  override val EquihashSolutionLength: Int = 1344

  override val powLimit: BigInteger = new BigInteger("0007ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 16)
  override val nPowAveragingWindow: Int = 17
  override val nPowMaxAdjustDown: Int = 32 // 32% adjustment down
  override val nPowMaxAdjustUp: Int = 16 // 16% adjustment up
  override val nPowTargetSpacing: Int = 150 // 2.5 * 60

  override val sidechainId: Array[Byte] = new Array[Byte](32) // TO DO: in future MainNetParams will be a class, where specific sidechainId will be set
}