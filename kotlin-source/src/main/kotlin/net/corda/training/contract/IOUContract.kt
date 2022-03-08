package net.corda.training.contract

import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.contracts.utils.sumCash
import net.corda.training.state.IOUState

/**
 * This is where you'll add the contract code which defines how the [IOUState] behaves. Look at the unit tests in
 * [IOUContractTests] for instructions on how to complete the [IOUContract] class.
 */
class IOUContract : Contract {
    companion object {
        @JvmStatic
        val IOU_CONTRACT_ID = "net.corda.training.contract.IOUContract"
        // ^ what's this for? something to do with deserialisation? required for tx output
    }

    /**
     * Add any commands required for this contract as classes within this interface.
     * It is useful to encapsulate your commands inside an interface, so you can use the [requireSingleCommand]
     * function to check for a number of commands which implement this interface.
     */
    interface Commands : CommandData {
        class Issue: TypeOnlyCommandData(), Commands
        class Transfer: TypeOnlyCommandData(), Commands
        class Settle: TypeOnlyCommandData(), Commands
    }

    /**
     * The contract code for the [IOUContract].
     * The constraints are self documenting so don't require any additional explanation.
     */
    override fun verify(tx: LedgerTransaction) {
        val cmd = tx.commands.requireSingleCommand<Commands>()
        when(cmd.value) {
            is Commands.Issue -> {
                requireThat {
                    "No inputs should be consumed when issuing an IOU." using tx.inputStates.isEmpty()
                    "Only one output state should be created when issuing an IOU." using (tx.outputStates.size == 1)
                }
                val state = tx.outputsOfType<IOUState>().single()
                val signers = cmd.signers.toSet()
                val parties = setOf(state.lender.owningKey, state.borrower.owningKey)

                requireThat {
                    "A newly issued IOU must have a positive amount." using (state.amount.quantity > 0)
                    "The lender and borrower cannot have the same identity." using (state.lender != state.borrower)
                    "Both lender and borrower together only may sign IOU issue transaction." using (signers == parties)
                }
            }
            is Commands.Transfer -> {
                val s1 = tx.inputsOfType<IOUState>().single()
                val s2 = tx.outputsOfType<IOUState>().single()
                val signers = cmd.signers.toSet()
                val parties = setOf(s1.lender.owningKey, s2.lender.owningKey, s2.borrower.owningKey)

                requireThat {
                    "An IOU transfer transaction should only consume one input state." using (tx.inputStates.size == 1)
                    "An IOU transfer transaction should only create one output state." using (tx.outputStates.size == 1)
                    "Only the lender property may change." using (s2.copy(lender = s1.lender) == s1)
                    "The lender property must change in a transfer." using (s1.lender != s2.lender)
                    "The borrower, old lender and new lender only must sign an IOU transfer transaction" using (signers == parties)
                }
            }

            is Commands.Settle -> {
                val grouped = tx.groupStates<IOUState, UniqueIdentifier> { it.linearId }.single()
                val iou = grouped.inputs.first()
                val lender = iou.lender
                val outputCash = tx.outputsOfType<Cash.State>()
                val cashToLender = outputCash.filter { it.owner == lender }

                requireThat {
                    ("There must be one input IOU."
                            using (grouped.inputs.size == 1))
                    ("There must be output cash."
                            using outputCash.isNotEmpty())
                    ("Output cash must be paid to the lender."
                            using cashToLender.isNotEmpty())
                    ("The amount settled cannot be more than the amount outstanding."
                            using (cashToLender.sumCash().withoutIssuer() <= iou.amount))
                }
            }
        }
    }
}