package net.corda.training.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.training.contract.IOUContract
import net.corda.training.state.IOUState

/**
 * This is the flow which handles transfers of existing IOUs on the ledger.
 * Gathering the counterparty's signature is handled by the [CollectSignaturesFlow].
 * Notarisation (if required) and commitment to the ledger is handled by the [FinalityFlow].
 * The flow returns the [SignedTransaction] that was committed to the ledger.
 */
@InitiatingFlow
@StartableByRPC
class IOUTransferFlow(val linearId: UniqueIdentifier, val newLender: Party): FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        // retrieve input state from vault
        val inputStateAndRef = retrieveInputState()
        val inputState = inputStateAndRef.state.data

        // flow must be initiated by the current lender. Is this the right way to check that?
        val initiator = serviceHub.myInfo.legalIdentities.first()
        if (initiator != inputState.lender) {
            throw IllegalArgumentException("Transfer flow must be initiated by the original lender.")
        }

        // create command, with 3 signatories (borrower, old lender, new lender)
        val parties = listOf(inputState.borrower, inputState.lender, newLender)
        val signers = parties.map { it.owningKey }
        val cmd = Command(IOUContract.Commands.Transfer(), signers)

        // create output state with new lender
        val outputState = inputState.withNewLender(newLender)

        // create tx builder
        val builder = TransactionBuilder(notary = serviceHub.networkMapCache.notaryIdentities.first())
            .addCommand(cmd)
            .addInputState(inputStateAndRef)
            .addOutputState(outputState, IOUContract.IOU_CONTRACT_ID)
        builder.verify(serviceHub)

        // initial signature
        val initialTx = serviceHub.signInitialTransaction(builder)

        // collect signatures
        val otherSigners = parties.filter { it.owningKey != ourIdentity.owningKey }
        val flowSessions = otherSigners.map { initiateFlow(it) }
        val signedTx = subFlow(CollectSignaturesFlow(initialTx, flowSessions))

        // finalise
        return subFlow(FinalityFlow(signedTx, flowSessions))
    }

    private fun retrieveInputState(): StateAndRef<IOUState> {
        // get input state from vault (is there a simpler way to do this?)
        val linearStateCriteria = LinearStateQueryCriteria(linearId = listOf(linearId), status = Vault.StateStatus.ALL)
        val vaultCriteria = VaultQueryCriteria(status = Vault.StateStatus.ALL)
        return serviceHub.vaultService.queryBy<IOUState>(linearStateCriteria and vaultCriteria).states[0]
    }
}

/**
 * This is the flow which signs IOU transfers.
 * The signing is handled by the [SignTransactionFlow].
 */
@InitiatedBy(IOUTransferFlow::class)
class IOUTransferFlowResponder(val flowSession: FlowSession): FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an IOU transaction" using (output is IOUState)
            }
        }

        val signed = subFlow(signedTransactionFlow)
        // not 100% sure the following is correct, but it passes the tests
        subFlow(ReceiveFinalityFlow(flowSession, signed.tx.id))
    }
}