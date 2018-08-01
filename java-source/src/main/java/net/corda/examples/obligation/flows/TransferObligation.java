package net.corda.examples.obligation.flows;

import co.paralleluniverse.fibers.Suspendable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import kotlin.Triple;
import net.corda.confidential.IdentitySyncFlow;
import net.corda.confidential.SwapIdentitiesFlow;
import net.corda.core.contracts.Command;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.flows.*;
import net.corda.core.identity.AbstractParty;
import net.corda.core.identity.AnonymousParty;
import net.corda.core.identity.Party;
import net.corda.core.serialization.CordaSerializable;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.TransactionBuilder;
import net.corda.core.transactions.WireTransaction;
import net.corda.core.utilities.ProgressTracker;
import net.corda.core.utilities.ProgressTracker.Step;
import net.corda.examples.obligation.Obligation;
import net.corda.examples.obligation.ObligationContract;
import net.corda.examples.obligation.flows.ObligationBaseFlow.SignTxFlowNoChecking;

import javax.validation.Payload;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static net.corda.examples.obligation.ObligationContract.OBLIGATION_CONTRACT_ID;

public class TransferObligation {
    @CordaSerializable
    public class Payload {
        public final Party borrower;
        public final Party newLender;
        public final WireTransaction tx;
        public Payload(Party borrower, Party newLender, WireTransaction tx) {
            this.borrower = borrower;
            this.newLender = newLender;
            this.tx = tx;
        }
    }

    @StartableByRPC
    @InitiatingFlow
    public static class Initiator extends ObligationBaseFlow {
        private final UniqueIdentifier linearId;
        private final Party newLender;
        private final Boolean anonymous;

        private final Step PREPARATION = new Step("Obtaining Obligation from vault.");
        private final Step BUILDING = new Step("Building and verifying transaction.");
        private final Step SIGNING = new Step("Signing transaction.");
        private final Step SYNCING = new Step("Syncing identities.") {
            @Override
            public ProgressTracker childProgressTracker() {
                return IdentitySyncFlow.Send.Companion.tracker();
            }
        };
        private final Step COLLECTING = new Step("Collecting counterparty signature.") {
            @Override
            public ProgressTracker childProgressTracker() {
                return CollectSignaturesFlow.Companion.tracker();
            }
        };
        private final Step FINALISING = new Step("Finalising transaction.") {
            @Override
            public ProgressTracker childProgressTracker() {
                return FinalityFlow.Companion.tracker();
            }
        };

        private final ProgressTracker progressTracker = new ProgressTracker(
                PREPARATION, BUILDING, SIGNING, SYNCING, COLLECTING, FINALISING
        );

        public Initiator(UniqueIdentifier linearId, Party newLender, Boolean anonymous) {
            this.linearId = linearId;
            this.newLender = newLender;
            this.anonymous = anonymous;
        }

        @Override
        public ProgressTracker getProgressTracker() {
            return progressTracker;
        }

        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException {
            // Stage 1. Retrieve obligation specified by linearId from the vault.
            progressTracker.setCurrentStep(PREPARATION);
            final StateAndRef<Obligation> obligationToTransfer = getObligationByLinearId(linearId);
            final Obligation inputObligation = obligationToTransfer.getState().getData();

            // Stage 2. This flow can only be initiated by the current recipient.
            final AbstractParty lenderIdentity = getLenderIdentity(inputObligation);

            // Stage 3. Abort if the borrower started this flow.
            if (!getOurIdentity().equals(lenderIdentity)) {
                throw new IllegalStateException("Obligation transfer can only be initiated by the lender.");
            }

            // Stage 4. Create the new obligation state reflecting a new lender.
            progressTracker.setCurrentStep(BUILDING);
            final Obligation transferredObligation = createOutputObligation(inputObligation);

            // Stage 4. Create the transfer command.
            final List<PublicKey> signerKeys = new ImmutableList.Builder<PublicKey>()
                    .addAll(inputObligation.getParticipantKeys())
                    .add(transferredObligation.getLender().getOwningKey()).build();
            final Command transferCommand = new Command<>(new ObligationContract.Commands.Transfer(), signerKeys);

            // Stage 5. Create a transaction builder, then add the states and commands.
            final TransactionBuilder builder = new TransactionBuilder(getFirstNotary())
                    .addInputState(obligationToTransfer)
                    .addOutputState(transferredObligation, OBLIGATION_CONTRACT_ID)
                    .addCommand(transferCommand);

            // Stage 6. Verify and sign the transaction.
            progressTracker.setCurrentStep(SIGNING);
            builder.verify(getServiceHub());
            final SignedTransaction ptx = getServiceHub().signInitialTransaction(builder, inputObligation.getLender().getOwningKey());

            // Stage 7. Get a Party object for the borrower.
            progressTracker.setCurrentStep(SYNCING);
            final Party borrower = getBorrowerIdentity(inputObligation);

            // Stage 8. Send any keys and certificates so the signers can verify each other's identity.
            // We call `toSet` in case the borrower and the new lender are the same party.
            Set<FlowSession> sessions = new HashSet<>();
            Set<Party> parties = ImmutableSet.of(borrower, newLender);
            FlowSession borrowerSession = initiateFlow(borrower);
            FlowSession newLenderSession = initiateFlow(newLender);
            sessions.add(borrowerSession);
            sessions.add(newLenderSession);

            // This is to send to the transaction payload to the borrower and newlender so that they can
            // sync identities with each other.
            // We need to send the well-known parties borrower and newlender in the payload because the transaction itself
            // only has borrower and lender as AbstractParty which may be anonymous.
            Triple p = new Triple(borrower, this.newLender, ptx.getTx());
            borrowerSession.send(p);
            newLenderSession.send(p);

            // for the lender, we still use the original IdentitySynchFlow
            subFlow(new IdentitySyncFlow.Send(sessions, ptx.getTx(), SYNCING.childProgressTracker()));

            // Stage 9. Collect signatures from the borrower and the new lender.
            progressTracker.setCurrentStep(COLLECTING);
            final SignedTransaction stx = subFlow(new CollectSignaturesFlow(
                    ptx,
                    sessions,
                    ImmutableList.of(inputObligation.getLender().getOwningKey()),
                    COLLECTING.childProgressTracker()));

            // Stage 10. Notarise and record, the transaction in our vaults. Send a copy to me as well.
            progressTracker.setCurrentStep(FINALISING);
            return subFlow(new FinalityFlow(stx, ImmutableSet.of(getOurIdentity())));
        }

        @Suspendable
        private AbstractParty getLenderIdentity(Obligation inputObligation) {
            if (inputObligation.getLender() instanceof AnonymousParty) {
                return resolveIdentity(inputObligation.getLender());
            } else {
                return inputObligation.getLender();
            }
        }

        @Suspendable
        private Obligation createOutputObligation(Obligation inputObligation) throws FlowException {
            if (anonymous) {
                // TODO: Is there a flow to get a key and cert only from the counterparty?
                final HashMap<Party, AnonymousParty> txKeys = subFlow(new SwapIdentitiesFlow(newLender));
                if (!txKeys.containsKey(newLender)) {
                    throw new FlowException("Couldn't get lender's conf. identity.");
                }
                final AnonymousParty anonymousLender = txKeys.get(newLender);
                return inputObligation.withNewLender(anonymousLender);
            } else {
                return inputObligation.withNewLender(newLender);
            }
        }

        @Suspendable
        private Party getBorrowerIdentity(Obligation inputObligation) {
            if (inputObligation.getBorrower() instanceof AnonymousParty) {
                return resolveIdentity(inputObligation.getBorrower());
            } else {
                return (Party) inputObligation.getBorrower();
            }
        }
    }

    @InitiatedBy(Initiator.class)
    public static class Responder extends FlowLogic<SignedTransaction> {
        private final FlowSession otherFlow;
//        private final Step SYNCING = new Step("Syncing identities.") {
//            @Override
//            public ProgressTracker childProgressTracker() { return IdentitySyncFlowWrapper.Initiator.getProgressTracker(); }
//        };
//        private ProgressTracker progressTracker = new ProgressTracker(SYNCING);

        public Responder(FlowSession otherFlow) {
            this.otherFlow = otherFlow;
        }

        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException {
            // Stage 1. Receive the triple payload from current lender and have borrower and new lender
            // sync each other's identity.
            // this one works
//            Boolean p = otherFlow.receive(Boolean.class).unwrap(data -> data);

            Triple<Party, Party, WireTransaction> triple = otherFlow.receive(Triple.class).unwrap(data -> data);
//            Triple<Party, Party, WireTransaction> triple = otherFlow.receive(Triple.class).unwrap(data -> new Triple((Party) data.getFirst(), (Party) data.getSecond(), (WireTransaction) data.getThird()) );
            Party borrower = triple.getFirst();
            Party newlender = triple.getSecond();
            WireTransaction tx = triple.getThird();
            Party me = getOurIdentity();
            Party otherParty;
            if (me.getName().equals(borrower.getName())) {
                otherParty = newlender;
            }
            else if (me.getName().equals(newlender.getName())) {
                otherParty = borrower;
            }
            else throw new FlowException("Unknown borrower or newlender.");

            subFlow(new IdentitySyncFlowWrapper.Initiator(otherParty, tx));
//            subFlow(new IdentitySyncFlowWrapper.Initiator(otherParty, tx, SYNCING.childProgressTracker()));

            subFlow(new IdentitySyncFlow.Receive(otherFlow));
            SignedTransaction stx = subFlow(new SignTxFlowNoChecking(otherFlow, SignTransactionFlow.Companion.tracker()));
            return waitForLedgerCommit(stx.getId());
        }
    }
}