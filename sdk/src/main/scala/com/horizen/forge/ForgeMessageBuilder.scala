package com.horizen.forge

import com.horizen.block._
import com.horizen.box.{ForgerBox, NoncedBox}
import com.horizen.chain.{MainchainHeaderHash, SidechainBlockInfo}
import com.horizen.companion.SidechainTransactionsCompanion
import com.horizen.consensus._
import com.horizen.params.{NetworkParams, RegTestParams}
import com.horizen.proof.VrfProof
import com.horizen.proposition.Proposition
import com.horizen.secret.{PrivateKey25519, VrfSecretKey}
import com.horizen.transaction.SidechainTransaction
import com.horizen.utils.MerklePath
import com.horizen.{SidechainHistory, SidechainMemoryPool, SidechainState, SidechainWallet}
import scorex.core.NodeViewHolder.ReceivableMessages.GetDataFromCurrentView
import scorex.util.{ModifierId, ScorexLogging}
import com.horizen.chain._

import scala.util.{Failure, Success, Try}

class ForgeMessageBuilder(mainchainSynchronizer: MainchainSynchronizer,
                          companion: SidechainTransactionsCompanion,
                          val params: NetworkParams) extends ScorexLogging with TimeToEpochSlotConverter {
  type ForgeMessageType = GetDataFromCurrentView[SidechainHistory, SidechainState, SidechainWallet, SidechainMemoryPool, ForgeResult]

  case class BranchPointInfo(branchPointId: ModifierId, referenceDataToInclude: Seq[MainchainHeaderHash], headersToInclude: Seq[MainchainHeaderHash])

  def buildForgeMessageForEpochAndSlot(consensusEpochNumber: ConsensusEpochNumber, consensusSlotNumber: ConsensusSlotNumber): ForgeMessageType = {
      val forgingFunctionForEpochAndSlot: View => ForgeResult = tryToForgeNextBlock(consensusEpochNumber, consensusSlotNumber)

      val forgeMessage: ForgeMessageType =
        GetDataFromCurrentView[SidechainHistory, SidechainState, SidechainWallet, SidechainMemoryPool, ForgeResult](forgingFunctionForEpochAndSlot)

      forgeMessage
  }

  protected def tryToForgeNextBlock(nextConsensusEpochNumber: ConsensusEpochNumber, nextConsensusSlotNumber: ConsensusSlotNumber)(nodeView: View): ForgeResult = {
    log.info(s"Try to forge block for epoch $nextConsensusEpochNumber with slot $nextConsensusSlotNumber")

    val branchPointInfo: BranchPointInfo = getBranchPointInfo(nodeView.history) match {
      case Success(info) => info
      case Failure(ex) => return ForgeFailed(ex)
    }

    val parentBlockId: ModifierId = branchPointInfo.branchPointId
    val parentBlockInfo = nodeView.history.blockInfoById(parentBlockId)

    checkNextEpochAndSlot(parentBlockInfo.timestamp, nodeView.history.bestBlockInfo.timestamp, nextConsensusEpochNumber, nextConsensusSlotNumber) match {
      case Failure(ex) => ForgeFailed(ex)
      case _ =>

        val nextBlockTimestamp = getTimeStampForEpochAndSlot(nextConsensusEpochNumber, nextConsensusSlotNumber)
        val consensusInfo: FullConsensusEpochInfo = nodeView.history.getFullConsensusEpochInfoForNextBlock(parentBlockId, nextConsensusEpochNumber)
        val totalStake = consensusInfo.stakeConsensusEpochInfo.totalStake
        val vrfMessage = buildVrfMessage(nextConsensusSlotNumber, consensusInfo.nonceConsensusEpochInfo)

        val sidechainWallet = nodeView.vault

        val forgerBoxMerklePathInfoSeq: Seq[(ForgerBox, MerklePath)]
        = sidechainWallet.getForgerBoxMerklePathInfoOpt(nextConsensusEpochNumber).getOrElse(Seq()).map(d => (d.forgerBox, d.merklePath))

        val ownedForgingDataView: Seq[(ForgerBox, MerklePath, PrivateKey25519, VrfProof)]
        = forgerBoxMerklePathInfoSeq.view.flatMap{case (forgerBox, merklePath) => getSecretsAndProof(sidechainWallet, vrfMessage, forgerBox, merklePath)}

        val eligibleForgingDataView: Seq[(ForgerBox, MerklePath, PrivateKey25519, VrfProof)] =
          ownedForgingDataView.filter{case(forgerBox, merklePath, privateKey25519, vrfProof) => vrfProofCheckAgainstStake(vrfProof, forgerBox.vrfPubKey(), vrfMessage, forgerBox.value(), totalStake)}

        val eligibleForgerOpt = eligibleForgingDataView.headOption //force all forging related calculations

        val forgingResult = eligibleForgerOpt
          .map{case (forgerBox, merklePath, privateKey25519, vrfProof) =>
            forgeBlock(nodeView, nextBlockTimestamp, branchPointInfo, forgerBox, merklePath, privateKey25519, vrfProof)}
          .getOrElse(SkipSlot)

        log.info(s"Forge result is: $forgingResult")
        forgingResult
    }
  }

  private def getSecretsAndProof(wallet: SidechainWallet,
                                 vrfMessage: VrfMessage,
                                 forgerBox: ForgerBox,
                                 merklePath: MerklePath): Option[(ForgerBox, MerklePath, PrivateKey25519, VrfProof)] = {
    for {
      rewardPrivateKey <- wallet.secret(forgerBox.rewardProposition()).asInstanceOf[Option[PrivateKey25519]]
      vrfSecret <- wallet.secret(forgerBox.vrfPubKey()).asInstanceOf[Option[VrfSecretKey]]
    } yield (forgerBox, merklePath, rewardPrivateKey, vrfSecret.prove(vrfMessage))
  }

  private def checkNextEpochAndSlot(parentBlockTimesStamp: Long,
                                    bestBlockTimestamp: Long,
                                    nextEpochNumber: ConsensusEpochNumber,
                                    nextSlotNumber: ConsensusSlotNumber): Try[Unit] = {
    val parentBlockEpochAndSlot: ConsensusEpochAndSlot = timestampToEpochAndSlot(parentBlockTimesStamp)
    val bestBlockEpochAndSlot: ConsensusEpochAndSlot = timestampToEpochAndSlot(bestBlockTimestamp)
    val nextBlockEpochAndSlot: ConsensusEpochAndSlot = ConsensusEpochAndSlot(nextEpochNumber, nextSlotNumber)

    if(parentBlockEpochAndSlot >= nextBlockEpochAndSlot) {
      Failure(new IllegalArgumentException (s"Try to forge block with incorrect epochAndSlot $nextBlockEpochAndSlot which are equal or less than parent block epochAndSlot: $parentBlockEpochAndSlot"))
    }
    else if ((nextEpochNumber - parentBlockEpochAndSlot.epochNumber) > 1) {
      Failure(new IllegalArgumentException (s"Forging is not possible, because of whole consensus epoch is missed: current epoch = $nextEpochNumber, parent epoch = ${parentBlockEpochAndSlot.epochNumber}"))
    }
    else if(bestBlockEpochAndSlot >= nextBlockEpochAndSlot) {
      Failure(new IllegalArgumentException (s"Try to forge block with incorrect epochAndSlot $nextBlockEpochAndSlot which are equal or less than last ommer epochAndSlot: $bestBlockEpochAndSlot"))
    }
    else
      Success()
  }


  private def getBranchPointInfo(history: SidechainHistory): Try[BranchPointInfo] = Try {
    val bestMainchainHeaderInfo = history.getBestMainchainHeaderInfo.get

    val (bestKnownHashHeight: Int, newHeaderHashes: Seq[MainchainHeaderHash]) =
      mainchainSynchronizer.getMainchainDivergentSuffix(history, MainchainSynchronizer.MAX_BLOCKS_REQUEST) match {
        case Success((height, hashes)) => (height, hashes.tail) // hashes contains also the hash of best known block
        case Failure(ex) =>
          // For regtest Forger is allowed to produce next block in case if there is no MC Node connection
          if (params.isInstanceOf[RegTestParams])
            (bestMainchainHeaderInfo.height, Seq())
          else
            throw ex
      }

    // Check that there is no orphaned mainchain headers: SC most recent mainchain header is a part of MC active chain
    if(bestKnownHashHeight == bestMainchainHeaderInfo.height) {
      val branchPointId: ModifierId = history.bestBlockId
      var withdrawalEpochMcBlocksLeft = params.withdrawalEpochLength - history.bestBlockInfo.withdrawalEpochInfo.lastEpochIndex
      if (withdrawalEpochMcBlocksLeft == 0) // current best block is the last block of the epoch
        withdrawalEpochMcBlocksLeft = params.withdrawalEpochLength

      val maxReferenceDataNumber: Int = Math.min(SidechainBlock.MAX_MC_BLOCKS_NUMBER, withdrawalEpochMcBlocksLeft) // to not to include mcblock references from different withdrawal epochs

      val missedMainchainReferenceDataHeaderHashes: Seq[MainchainHeaderHash] = history.missedMainchainReferenceDataHeaderHashes
      val nextMainchainReferenceDataHeaderHashes: Seq[MainchainHeaderHash] = missedMainchainReferenceDataHeaderHashes ++ newHeaderHashes

      val mainchainReferenceDataHeaderHashesToInclude = nextMainchainReferenceDataHeaderHashes.take(maxReferenceDataNumber)
      val mainchainHeadersHashesToInclude = mainchainReferenceDataHeaderHashesToInclude.filter(hash => !missedMainchainReferenceDataHeaderHashes.contains(hash))

      BranchPointInfo(branchPointId, mainchainReferenceDataHeaderHashesToInclude, mainchainHeadersHashesToInclude)
    }
    else { // Some blocks in SC Active chain contains orphaned MainchainHeaders
      val orphanedMainchainHeadersNumber: Int = bestMainchainHeaderInfo.height - bestKnownHashHeight
      val newMainchainHeadersNumber = newHeaderHashes.size

      if (orphanedMainchainHeadersNumber >= newMainchainHeadersNumber) {
        ForgeFailed(new Exception("No sense to forge: active branch contains orphaned MainchainHeaders, that number is greater or equal to actual new MainchainHeaders."))
      }

      val firstOrphanedHashHeight: Int = bestKnownHashHeight + 1
      val firstOrphanedMainchainHeaderInfo = history.getMainchainHeaderInfoByHeight(firstOrphanedHashHeight).get
      val orphanedSidechainBlockId: ModifierId = firstOrphanedMainchainHeaderInfo.sidechainBlockId
      val orphanedSidechainBlockInfo: SidechainBlockInfo = history.blockInfoById(orphanedSidechainBlockId)

      if (firstOrphanedMainchainHeaderInfo.hash.equals(orphanedSidechainBlockInfo.mainchainHeaderHashes.head)) {
        // First Unknown MainchainHeader is the first header inside the containing SidechainBlock, so no common MainchainHeaders present in SidechainBlock before it
        BranchPointInfo(orphanedSidechainBlockInfo.parentId, Seq(), newHeaderHashes)
      }
      else {
        // SidechainBlock also contains some common MainchainHeaders before first orphaned MainchainHeader
        // So we should add that common MainchainHeaders to the SidechainBlock as well
        BranchPointInfo(orphanedSidechainBlockInfo.parentId, Seq(),
          orphanedSidechainBlockInfo.mainchainHeaderHashes.takeWhile(hash => !hash.equals(firstOrphanedMainchainHeaderInfo.hash)) ++ newHeaderHashes)
      }
    }
  }

  private def forgeBlock(nodeView: View,
                         timestamp: Long,
                         branchPointInfo: BranchPointInfo,
                         forgerBox: ForgerBox,
                         merklePath: MerklePath,
                         forgerBoxRewardPrivateKey: PrivateKey25519,
                         vrfProof: VrfProof): ForgeResult = {
    val parentBlockId: ModifierId = branchPointInfo.branchPointId
    val parentBlockInfo: SidechainBlockInfo = nodeView.history.blockInfoById(branchPointInfo.branchPointId)
    var withdrawalEpochMcBlocksLeft: Int = params.withdrawalEpochLength - parentBlockInfo.withdrawalEpochInfo.lastEpochIndex
    if (withdrawalEpochMcBlocksLeft == 0) // parent block is the last block of the epoch
      withdrawalEpochMcBlocksLeft = params.withdrawalEpochLength

    // Get all needed MainchainBlockReferences from MC Node
    val mainchainHashesToRetrieve: Seq[MainchainHeaderHash] = (branchPointInfo.referenceDataToInclude ++ branchPointInfo.headersToInclude).distinct
    val mainchainBlockReferences: Seq[MainchainBlockReference] =
      mainchainSynchronizer.getMainchainBlockReferences(nodeView.history, mainchainHashesToRetrieve) match {
        case Success(references) => references
        case Failure(ex) => return ForgeFailed(ex)
      }

    // Extract proper MainchainReferenceData
    val mainchainReferenceData: Seq[MainchainBlockReferenceData] =
      mainchainBlockReferences.withFilter(ref => branchPointInfo.referenceDataToInclude.contains(byteArrayToMainchainHeaderHash(ref.header.hash)))
      .map(_.data)

    // Extract proper MainchainHeaders
    val mainchainHeaders: Seq[MainchainHeader] =
      mainchainBlockReferences.withFilter(ref => branchPointInfo.headersToInclude.contains(byteArrayToMainchainHeaderHash(ref.header.hash)))
        .map(_.header)

    // Get transactions if possible
    val transactions: Seq[SidechainTransaction[Proposition, NoncedBox[Proposition]]] =
      if (branchPointInfo.referenceDataToInclude.size == withdrawalEpochMcBlocksLeft) { // SC block is going to become the last block of the withdrawal epoch
        Seq() // no SC Txs allowed
      } else { // SC block is in the middle of the epoch
        nodeView.pool.take(SidechainBlock.MAX_SIDECHAIN_TXS_NUMBER) // TO DO: problems with types
          .map(t => t.asInstanceOf[SidechainTransaction[Proposition, NoncedBox[Proposition]]])
          .toSeq
      }

    // Get ommers in case if branch point is not current best block
    var ommers: Seq[Ommer] = Seq()
    var blockId = nodeView.history.bestBlockId
    while (blockId != branchPointInfo.branchPointId) {
      val block = nodeView.history.getBlockById(blockId).get() // TODO: replace with method blockById with no Option
      blockId = block.parentId
      ommers = Ommer.toOmmer(block) +: ommers
    }

    val tryBlock = SidechainBlock.create(
      parentBlockId,
      timestamp,
      mainchainReferenceData,
      transactions,
      mainchainHeaders,
      ommers,
      forgerBoxRewardPrivateKey,
      forgerBox,
      vrfProof,
      merklePath,
      companion,
      params)

    tryBlock match {
      case Success(block) => ForgeSuccess(block)
      case Failure(exception) => ForgeFailed(exception)
    }
  }
}




