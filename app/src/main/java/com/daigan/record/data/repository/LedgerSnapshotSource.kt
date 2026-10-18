package com.daigan.record.data.repository

import androidx.room.withTransaction
import com.daigan.record.data.local.RecordDatabase
import com.daigan.record.data.local.dao.GameAccountDao
import com.daigan.record.data.local.dao.OrderDao
import com.daigan.record.data.local.dao.OwnerDao
import com.daigan.record.data.local.dao.ReceiptDao
import com.daigan.record.data.local.entity.GameAccountEntity
import com.daigan.record.data.local.entity.HostingPeriodEntity
import com.daigan.record.data.local.entity.OrderEntity
import com.daigan.record.data.local.entity.OwnerEntity
import com.daigan.record.data.local.entity.ReceiptCorrectionEntity
import com.daigan.record.data.local.entity.ReceiptEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge

/**
 * Complete financial snapshot. Archived owners and accounts are included on purpose:
 * outstanding debt and receipt history must never disappear when a record is archived.
 */
data class LedgerSnapshot(
    val orders: List<OrderEntity>,
    val periods: List<HostingPeriodEntity>,
    val receipts: List<ReceiptEntity>,
    val corrections: List<ReceiptCorrectionEntity>,
    val accounts: List<GameAccountEntity>,
    val owners: List<OwnerEntity>,
)

/**
 * Emits one **transactionally consistent** ledger snapshot per database change.
 *
 * The tables are read inside a single `withTransaction` block instead of combining
 * several independent DAO flows. Combining those flows could publish a new receipt
 * together with an older order/period list (each query re-runs on its own schedule),
 * which would temporarily show a receipt without its order, drop history entries or
 * make the integrity checks terminate the subscription. Reading all tables in one
 * transaction means every emission is a complete, self-consistent version.
 *
 * Invalidation still comes from Room's own table observations: a write to any of the
 * six tables re-reads the whole snapshot. There is no debounce or delay hiding a real
 * inconsistency.
 */
@Singleton
class LedgerSnapshotSource @Inject constructor(
    private val database: RecordDatabase,
    private val orderDao: OrderDao,
    private val receiptDao: ReceiptDao,
    private val accountDao: GameAccountDao,
    private val ownerDao: OwnerDao,
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<LedgerSnapshot> = invalidations().mapLatest {
        database.withTransaction { readSnapshot() }
    }

    /** One tick per committed change in any table the snapshot is built from. */
    private fun invalidations(): Flow<Unit> = merge(
        orderDao.observeAllOrders().map { Unit },
        orderDao.observeAllPeriodsFlow().map { Unit },
        receiptDao.observeAll().map { Unit },
        receiptDao.observeAllCorrections().map { Unit },
        accountDao.observeAll().map { Unit },
        ownerDao.observeAll().map { Unit },
    )

    /** Must run inside [observe]'s transaction; all reads then see one DB version. */
    suspend fun readSnapshot(): LedgerSnapshot = LedgerSnapshot(
        orders = orderDao.findAllOrders(),
        periods = orderDao.findAllPeriods(),
        receipts = receiptDao.findAll(),
        corrections = receiptDao.findAllCorrections(),
        accounts = accountDao.findAll(),
        owners = ownerDao.findAll(),
    )

    /** Direct consistent read for callers that need a snapshot without subscribing. */
    suspend fun load(): LedgerSnapshot = database.withTransaction { readSnapshot() }
}
