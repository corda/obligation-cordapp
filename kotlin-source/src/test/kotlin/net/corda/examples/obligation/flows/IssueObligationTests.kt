package net.corda.examples.obligation.flows

import net.corda.core.utilities.Try
import net.corda.examples.obligation.Obligation
import net.corda.finance.POUNDS
import net.corda.testing.internal.chooseIdentity
import org.junit.Test
import kotlin.test.assertEquals

class IssueObligationTests : ObligationTests() {

    @Test
    fun `Issue non-anonymous obligation successfully with non null remark`() {
        // Throw null pointer
        try {
            issueObligation(a, b, 1000.POUNDS, anonymous = false, remark = null)
            network.waitQuiescent()
        } catch (e: Exception) {

        }

        // If I don't run this subsequent transaction, the test runs successfully.
        // Somehow, any transaction after a Try.on will get stuck.

        // Should issue successfully.
        issueObligation(a, b, 1000.POUNDS, anonymous = false, remark = "Valid")
        network.waitQuiescent()
    }


    @Test
    fun `issue anonymous obligation successfully`() {
        val stx = issueObligation(a, b, 1000.POUNDS)

        val aIdentity = a.services.myInfo.chooseIdentity()
        val bIdentity = b.services.myInfo.chooseIdentity()

        network.waitQuiescent()

        val aObligation = a.services.loadState(stx.tx.outRef<Obligation>(0).ref).data as Obligation
        val bObligation = b.services.loadState(stx.tx.outRef<Obligation>(0).ref).data as Obligation

        assertEquals(aObligation, bObligation)

        val maybePartyALookedUpByA = a.services.identityService.requireWellKnownPartyFromAnonymous(aObligation.borrower)
        val maybePartyALookedUpByB = b.services.identityService.requireWellKnownPartyFromAnonymous(aObligation.borrower)

        assertEquals(aIdentity, maybePartyALookedUpByA)
        assertEquals(aIdentity, maybePartyALookedUpByB)

        val maybePartyCLookedUpByA = a.services.identityService.requireWellKnownPartyFromAnonymous(aObligation.lender)
        val maybePartyCLookedUpByB = b.services.identityService.requireWellKnownPartyFromAnonymous(aObligation.lender)

        assertEquals(bIdentity, maybePartyCLookedUpByA)
        assertEquals(bIdentity, maybePartyCLookedUpByB)
    }
}
