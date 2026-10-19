package com.daigan.record.data.repository

import androidx.room.withTransaction
import com.daigan.record.data.local.RecordDatabase
import com.daigan.record.data.local.entity.HostingPeriodEntity
import com.daigan.record.data.local.toModel
import com.daigan.record.data.model.CorrectionResult
import com.daigan.record.data.model.PaymentRequest
import com.daigan.record.data.model.PaymentResult
import com.daigan.record.data.model.ReceiptCorrectionRequest
import com.daigan.record.data.model.ReceiptHistoryEntry
import com.daigan.record.data.model.ReceiptStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface ReceiptRepository {

    /** Every receipt (active, voided, corrected), newest actual receipt first. */
    fun observeReceiptHistory(): Flow<List<ReceiptHistoryEntry>>

    /** Records one payment atomically. Retries with the same operation id are safe. */
    suspend fun recordPayment(request: PaymentRequest): PaymentResult

    /** Voids or amends an existing receipt, leaving an audit row behind. */
    suspend fun correctReceipt(request: ReceiptCorrectionRequest): CorrectionResult
}

@Singleton
class DefaultReceiptRepository @Inject constructor(
    private val database: RecordDatabase,
    private val ledger: ReceiptLedger,
    private val snapshotSource: LedgerSnapshotSource,
) : ReceiptRepository {

    override fun observeReceiptHistory(): Flow<List<ReceiptHistoryEntry>> =
        snapshotSource.observe().map { snapshot -> buildHistory(snapshot) }

    override suspend fun recordPayment(request: PaymentRequest): PaymentResult =
        DatasetGate.withWrite { database.withDatasetTransaction { ledger.record(request) } }

    override suspend fun correctReceipt(request: ReceiptCorrectionRequest): CorrectionResult =
        DatasetGate.withWrite { database.withDatasetTransaction { ledger.correct(request) } }

    private fun buildHistory(snapshot: LedgerSnapshot): List<ReceiptHistoryEntry> {
        val ordersById = snapshot.orders.associateBy { it.id }
        val accountsById = snapshot.accounts.associateBy { it.id }
        val ownersById = snapshot.owners.associateBy { it.id }
        val periodsById = snapshot.periods.associateBy { it.id }
        val correctionsByReceipt = snapshot.corrections.groupBy { it.receiptId }

        return snapshot.receipts.mapNotNull { receipt ->
            val order = ordersById[receipt.orderId] ?: return@mapNotNull null
            val account = accountsById[order.accountId] ?: return@mapNotNull null
            val owner = ownersById[account.ownerId] ?: return@mapNotNull null
            val period: HostingPeriodEntity? = receipt.periodId?.let { periodsById[it] }
            val correction = correctionsByReceipt[receipt.id]?.firstOrNull()
            ReceiptHistoryEntry(
                receipt = receipt.toModel(),
                ownerId = owner.id,
                ownerNumber = owner.toModel().displayNumber,
                ownerNickname = owner.nickname,
                accountId = account.id,
                gameName = account.gameName,
                server = account.server,
                orderTitle = order.title,
                orderType = order.type,
                periodIndex = period?.periodIndex,
                periodStart = period?.startDate,
                periodEnd = period?.endDate,
                correctedToCents = if (receipt.status == ReceiptStatus.REPLACED) {
                    correction?.newAmountCents
                } else {
                    null
                },
            )
        }.sortedWith(
            compareByDescending<ReceiptHistoryEntry> { it.receipt.receivedAt }
                .thenByDescending { it.receipt.createdAt }
                .thenBy { it.receipt.id },
        )
    }
}
