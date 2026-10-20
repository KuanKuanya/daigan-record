package com.kuankuan.daigan.data.model

import com.kuankuan.daigan.testing.account
import com.kuankuan.daigan.testing.owner
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The archived-record summary must distinguish accounts archived together with their
 * owner from accounts that were archived on their own (or whose legacy provenance is
 * unknown). Counts are the only thing the UI wording is allowed to claim.
 */
class OwnerSummaryTest {

    @Test
    fun `counts separate owner-archived accounts from independent archives`() {
        val summary = OwnerSummary(
            owner = owner(archived = true),
            accounts = listOf(
                account(id = "a1", archived = true, archivedWithOwner = true),
                account(id = "a2", archived = true, archivedWithOwner = true),
                account(id = "a3", archived = true, archivedWithOwner = false),
            ),
        )

        assertEquals(3, summary.activeAccountCount)
        assertEquals(2, summary.accountsArchivedWithOwnerCount)
        assertEquals(1, summary.independentlyArchivedAccountCount)
    }

    @Test
    fun `active accounts are never counted as archived`() {
        val summary = OwnerSummary(
            owner = owner(archived = false),
            accounts = listOf(account(archived = false), account(id = "a2", archived = false)),
        )

        assertEquals(0, summary.accountsArchivedWithOwnerCount)
        assertEquals(0, summary.independentlyArchivedAccountCount)
    }
}
