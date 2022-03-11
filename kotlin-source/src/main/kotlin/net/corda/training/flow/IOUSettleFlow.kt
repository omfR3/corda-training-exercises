package net.corda.training.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.OpaqueBytes
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.flows.CashIssueFlow
import net.corda.finance.workflows.asset.CashUtils
import net.corda.finance.workflows.getCashBalance
import net.corda.training.contract.IOUContract
import net.corda.training.state.IOUState
import java.util.*

/**
 * This is the flow which handles the (partial) settlement of existing IOUs on the ledger.
 * Gathering the counterparty's signature is handled by the [CollectSignaturesFlow].
 * Notarisation (if required) and commitment to the ledger is handled vy the [FinalityFlow].
 * The flow returns the [SignedTransaction] that was committed to the ledger.
 */
@InitiatingFlow
@StartableByRPC
class IOUSettleFlow(val linearId: UniqueIdentifier, val amount: Amount<Currency>): FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        // retrieve input state from vault
        val inputStateAndRef = retrieveInputState()
        val inputState = inputStateAndRef.state.data

        // validate that only the borrower can run the flow
        val myIdentity = serviceHub.myInfo.legalIdentitiesAndCerts.first()
        if (myIdentity.party != inputState.borrower) {
            throw IllegalArgumentException("IOU settlement flow can only be initiated by the borrower of the IOU.")
        }

        // validate that the borrower has sufficient cash to settle
        val cash = serviceHub.getCashBalance(amount.token)
        if (cash < amount) {
            throw IllegalArgumentException("Borrower has only $cash but needs $amount to settle.")
        }

        // create command, with 2 signatories...?
        val parties = inputState.participants
        val signers = parties.map { it.owningKey }
        val cmd = Command(IOUContract.Commands.Settle(), signers)

        // create tx builder
        val builder = TransactionBuilder(notary = serviceHub.networkMapCache.notaryIdentities.first())

        // create 2 output states
        CashUtils.generateSpend(serviceHub, builder, amount, myIdentity, inputState.lender)
        val newIouState = inputState.pay(amount)

        builder
            .addCommand(cmd)
            .addInputState(inputStateAndRef)
            .addOutputState(newIouState, IOUContract.IOU_CONTRACT_ID)
            .verify(serviceHub)

        // initial signature
        val initialTx = serviceHub.signInitialTransaction(builder)

        // collect signatures
        val flowSessions = listOf(initiateFlow(inputState.lender))
        val signedTx = subFlow(CollectSignaturesFlow(initialTx, flowSessions))

        // finalise
        return subFlow(FinalityFlow(signedTx, flowSessions))
    }

    private fun retrieveInputState(): StateAndRef<IOUState> {
        // get input state from vault (is there a simpler way to do this?)
        val linearStateCriteria =
            QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId), status = Vault.StateStatus.ALL)
        val vaultCriteria = QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.ALL)
        return serviceHub.vaultService.queryBy<IOUState>(linearStateCriteria and vaultCriteria).states[0]
    }
}

/**
 * This is the flow which signs IOU settlements.
 * The signing is handled by the [SignTransactionFlow].
 */
@InitiatedBy(IOUSettleFlow::class)
class IOUSettleFlowResponder(val flowSession: FlowSession): FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val outputStates = stx.tx.outputs.map { it.data::class.java.name }.toList()
                "There must be an IOU transaction." using (outputStates.contains(IOUState::class.java.name))
            }
        }

        val signed = subFlow(signedTransactionFlow)
        // not 100% sure the following is correct, but it passes the tests
        subFlow(ReceiveFinalityFlow(flowSession, signed.tx.id))
    }
}

@InitiatingFlow
@StartableByRPC
/**
 * Self issues the calling node an amount of cash in the desired currency.
 * Only used for demo/sample/training purposes!
 */
class SelfIssueCashFlow(val amount: Amount<Currency>) : FlowLogic<Cash.State>() {
    @Suspendable
    override fun call(): Cash.State {
        /** Create the cash issue command. */
        val issueRef = OpaqueBytes.of(0)
        /** Note: ongoing work to support multiple notary identities is still in progress. */
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        /** Create the cash issuance transaction. */
        val cashIssueTransaction = subFlow(CashIssueFlow(amount, issueRef, notary))
        /** Return the cash output. */
        return cashIssueTransaction.stx.tx.outputs.single().data as Cash.State
    }
}