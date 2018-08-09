package net.corda.examples.obligation.flows;

import net.corda.core.identity.Party;
import net.corda.core.serialization.CordaSerializable;
import net.corda.core.transactions.WireTransaction;

@CordaSerializable
public class Payload {
    private final Party borrower;
    private final Party newLender;
    private final WireTransaction tx;

    public Payload(Party borrower, Party newLender, WireTransaction tx) {
        this.borrower = borrower;
        this.newLender = newLender;
        this.tx = tx;
    }

    public Party getBorrower() {
        return borrower;
    }

    public Party getNewLender() {
        return newLender;
    }

    public WireTransaction getTx() {
        return tx;
    }
}