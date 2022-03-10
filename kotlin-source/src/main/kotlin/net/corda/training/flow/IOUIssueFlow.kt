package net.corda.training.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.training.contract.IOUContract
import net.corda.training.state.IOUState

/**
 * This is the flow which handles issuance of new IOUs on the ledger.
 * Gathering the counterparty's signature is handled by the [CollectSignaturesFlow].
 * Notarisation (if required) and commitment to the ledger is handled by the [FinalityFlow].
 * The flow returns the [SignedTransaction] that was committed to the ledger.
 */
@InitiatingFlow
@StartableByRPC
class IOUIssueFlow(val state: IOUState) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        // create command and add to transaction builder
        val signers = state.participants.map { it.owningKey }
        val cmd = Command(IOUContract.Commands.Issue(), signers)
        val builder = TransactionBuilder(notary = serviceHub.networkMapCache.notaryIdentities.first())
            .withItems(cmd, StateAndContract(state, IOUContract.IOU_CONTRACT_ID))
        builder.verify(serviceHub)

        // sign tx -- this finalises it. OR DOES IT??
        val initialTx = serviceHub.signInitialTransaction(builder)

        // collect signatures
        val otherSigners = state.participants.filter { it.owningKey != ourIdentity.owningKey }
        val flowSessions = otherSigners.map { initiateFlow(it) }
        val signedTx = subFlow(CollectSignaturesFlow(initialTx, flowSessions))
        return subFlow(FinalityFlow(signedTx, flowSessions))
    }
}

/**
 * This is the flow which signs IOU issuances.
 * The signing is handled by the [SignTransactionFlow].
 */
@InitiatedBy(IOUIssueFlow::class)
class IOUIssueFlowResponder(val flowSession: FlowSession): FlowLogic<Unit>() {
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