package com.daigan.record.testing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.room.Room
import com.daigan.record.core.time.BoundaryWaiter
import com.daigan.record.core.time.GameRefreshRules
import com.daigan.record.core.time.TimeProvider
import com.daigan.record.core.time.TimeTicker
import com.daigan.record.data.local.RecordDatabase
import com.daigan.record.data.model.AccountDraft
import com.daigan.record.data.model.GameAccount
import com.daigan.record.data.model.HostingOrderDraft
import com.daigan.record.data.model.LoginMethod
import com.daigan.record.data.model.Owner
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.ShortOrderDraft
import com.daigan.record.data.model.TaskDraft
import com.daigan.record.data.repository.AttachmentSync
import com.daigan.record.data.repository.DefaultAttachmentRepository
import com.daigan.record.data.repository.DefaultGameAccountRepository
import com.daigan.record.data.repository.DefaultOrderRepository
import com.daigan.record.data.repository.DefaultOwnerRepository
import com.daigan.record.data.repository.DisplayedHostingSnapshot
import java.io.File
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class MutableClock(var current: Instant) : TimeProvider {
    override fun now(): Instant = current

    fun set(instant: Instant) {
        current = instant
    }

    fun advanceSeconds(seconds: Long) {
        current = current.plusSeconds(seconds)
    }
}

/**
 * Scheduler substitute for tests. The ticker parks here instead of sleeping; the test
 * moves the fake clock and releases the barrier, so boundary crossings are
 * deterministic and never depend on wall-clock timing.
 *
 * Every parked waiter owns a [CompletableDeferred] gate, so one [advanceTo] wakes every
 * parked ticker instead of the single receiver a conflated channel would release. The
 * `awaitParked`/`awaitParkedAt` helpers take bounded timeouts so a missed signal fails
 * the test with a clear message instead of hanging it. No real sleeps are used to make
 * a wake-up "eventually" happen.
 */
class ManualBoundaryWaiter(private val clock: MutableClock) : BoundaryWaiter {
    private class Parked(val target: Instant, val gate: CompletableDeferred<Unit>)

    private val parkMonitor = Any()
    private val parked = mutableListOf<Parked>()

    /** Upper bound for parking helpers; exceeded only when a signal was lost. */
    private val parkTimeoutMillis: Long = 5_000

    override suspend fun await(now: Instant, target: Instant) {
        // The boundary was crossed between emitting and parking (a test advanced the
        // clock). Returning immediately lets the ticker re-read the clock and emit the
        // new value, instead of parking on a boundary that is already in the past.
        if (!target.isAfter(clock.now())) {
            yield()
            return
        }
        val gate = CompletableDeferred<Unit>()
        synchronized(parkMonitor) { parked += Parked(target, gate) }
        try {
            gate.await()
        } finally {
            synchronized(parkMonitor) { parked.removeAll { it.gate === gate } }
        }
    }

    /** Number of tickers currently parked on a boundary. */
    fun parkedCount(): Int = synchronized(parkMonitor) { parked.size }

    /** Suspends until at least [count] tickers are parked, or fails with a clear message. */
    suspend fun awaitParked(count: Int = 1) {
        awaitCondition("expected $count parked ticker(s)") { parkedCount() >= count }
    }

    /** Suspends until a ticker is parked on [target], or fails with a clear message. */
    suspend fun awaitParkedAt(target: Instant) {
        awaitCondition("expected a ticker parked on $target") {
            synchronized(parkMonitor) { parked.any { it.target == target } }
        }
    }

    /**
     * Bounded wait for [condition]. A missed wake-up fails the test with the expectation
     * in the message instead of hanging it, so a scheduler regression stays diagnosable.
     */
    private suspend fun awaitCondition(what: String, condition: () -> Boolean) {
        try {
            withTimeout(parkTimeoutMillis) {
                while (!condition()) yield()
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError(
                "Timed out after ${parkTimeoutMillis}ms waiting for a boundary signal: $what. " +
                    "A parked ticker was never woken.",
                timeout,
            )
        }
    }

    /** Moves the clock forward and wakes every parked ticker. */
    fun advanceTo(instant: Instant) {
        advanceTo(instant, parkedTargets = null)
    }

    /** Moves the clock forward and wakes every parked ticker parked on [target]. */
    fun advanceTo(instant: Instant, target: Instant) {
        advanceTo(instant, parkedTargets = setOf(target))
    }

    private fun advanceTo(instant: Instant, parkedTargets: Set<Instant>?) {
        // A fake clock never moves backwards: doing so would hide scheduler bugs and
        // could park a ticker on a boundary the clock has already passed.
        check(instant.isAfter(clock.now())) { "fake clock must advance, not rewind" }
        clock.set(instant)
        val waiting = synchronized(parkMonitor) {
            parked.filter { parkedTargets == null || it.target in parkedTargets }
        }
        waiting.forEach { it.gate.complete(Unit) }
    }
}

/**
 * The identity the Today page is showing right now: for every currently visible hosting
 * order, its latest period, the game date on screen and each task's occurrence slot key.
 * Bulk hosting operations receive this so a refresh boundary crossed between render and
 * tap cannot silently complete a different game day's or game week's tasks.
 */
suspend fun OrderTestEnvironment.hostingSnapshots(): List<DisplayedHostingSnapshot> =
    orderRepository.observeTodayHosting().first().map { order ->
        DisplayedHostingSnapshot(
            orderId = order.order.id,
            periodId = order.period.id,
            gameDate = order.today,
            occurrenceKeys = order.tasks.associate { it.task.id to it.slot.key },
        )
    }

/** The identity the order-detail page is showing for one hosting order. */
suspend fun OrderTestEnvironment.hostingSnapshot(orderId: String): DisplayedHostingSnapshot {
    val order = orderRepository.observeTodayHosting().first().first { it.order.id == orderId }
    return DisplayedHostingSnapshot(
        orderId = order.order.id,
        periodId = order.period.id,
        gameDate = order.today,
        occurrenceKeys = order.tasks.associate { it.task.id to it.slot.key },
    )
}

val shanghaiRules: GameRefreshRules = GameRefreshRules(
    zoneId = ZoneId.of("Asia/Shanghai"),
    dailyRefresh = LocalTime.of(4, 0),
    weeklyRefreshDay = DayOfWeek.MONDAY,
    weeklyRefreshTime = LocalTime.of(5, 0),
)

/** 2026-10-17 10:00 in Shanghai: game date 2026-10-17. */
val defaultTestInstant: Instant = Instant.parse("2026-10-17T02:00:00Z")

/**
 * Real Room + real repositories for one test.
 *
 * [inMemory] keeps data tests fast; persistence/attachment tests pass false so a
 * database file survives a close/reopen cycle.
 */
class OrderTestEnvironment(
    private val context: Context,
    initialInstant: Instant = defaultTestInstant,
    databaseName: String? = null,
    inMemory: Boolean = true,
) {
    val clock = MutableClock(initialInstant)
    val boundaryWaiter = ManualBoundaryWaiter(clock)
    val ticker = TimeTicker(clock, boundaryWaiter)
    val attachmentSync = AttachmentSync()
    val database: RecordDatabase
    val ownerRepository: DefaultOwnerRepository
    val accountRepository: DefaultGameAccountRepository
    val orderRepository: DefaultOrderRepository
    val attachmentRepository: DefaultAttachmentRepository
    private val dbName = databaseName

    init {
        val builder = if (inMemory) {
            Room.inMemoryDatabaseBuilder(context, RecordDatabase::class.java)
        } else {
            Room.databaseBuilder(
                context,
                RecordDatabase::class.java,
                checkNotNull(databaseName),
            ).addMigrations(*RecordDatabase.ALL_MIGRATIONS)
        }
        database = builder.build()
        ownerRepository = DefaultOwnerRepository(
            database = database,
            ownerDao = database.ownerDao(),
            accountDao = database.gameAccountDao(),
            orderDao = database.orderDao(),
            timeProvider = clock,
        )
        accountRepository = DefaultGameAccountRepository(
            database = database,
            ownerDao = database.ownerDao(),
            accountDao = database.gameAccountDao(),
            orderDao = database.orderDao(),
            timeProvider = clock,
        )
        orderRepository = DefaultOrderRepository(
            database = database,
            orderDao = database.orderDao(),
            taskDao = database.taskDao(),
            occurrenceDao = database.occurrenceDao(),
            batchDao = database.completionBatchDao(),
            attachmentDao = database.attachmentDao(),
            accountDao = database.gameAccountDao(),
            ownerDao = database.ownerDao(),
            ticker = ticker,
        )
        attachmentRepository = DefaultAttachmentRepository(
            context = context,
            database = database,
            attachmentDao = database.attachmentDao(),
            taskDao = database.taskDao(),
            occurrenceDao = database.occurrenceDao(),
            orderDao = database.orderDao(),
            accountDao = database.gameAccountDao(),
            ownerDao = database.ownerDao(),
            timeProvider = clock,
            sync = attachmentSync,
        )
    }

    suspend fun seedOwnerAndAccount(
        nickname: String = "小满",
        gameName: String = "重返未来：1999",
        uid: String = "10000628",
    ): Pair<Owner, GameAccount> {
        val owner = ownerRepository.createOwner(nickname)
        val account = accountRepository.createAccount(
            owner.id,
            AccountDraft(
                gameName = gameName,
                server = "官服",
                uid = uid,
                loginAccount = "login-$uid",
                loginMethod = LoginMethod.VERIFICATION_CODE,
            ),
        )
        return owner to account
    }

    fun close(deleteDatabaseFile: Boolean = true) {
        database.close()
        if (deleteDatabaseFile && dbName != null) context.deleteDatabase(dbName)
    }
}

fun hostingDraft(
    ownerId: String,
    accountId: String,
    start: LocalDate,
    end: LocalDate,
    priceYuan: String = "300.00",
    tasks: List<TaskDraft> = listOf(TaskDraft(title = "每日签到")),
    rules: GameRefreshRules = shanghaiRules,
    title: String = "日常托管",
): HostingOrderDraft = HostingOrderDraft(
    ownerId = ownerId,
    accountId = accountId,
    title = title,
    startDate = start,
    endDate = end,
    priceYuan = priceYuan,
    paymentMode = PaymentMode.ONE_TIME,
    refresh = rules,
    tasks = tasks,
)

fun shortDraft(
    ownerId: String,
    accountId: String,
    priceYuan: String = "100.00",
    deadline: LocalDate? = null,
    tasks: List<TaskDraft> = listOf(TaskDraft(title = "推进主线")),
    title: String = "短期任务",
): ShortOrderDraft = ShortOrderDraft(
    ownerId = ownerId,
    accountId = accountId,
    title = title,
    priceYuan = priceYuan,
    deadlineDate = deadline,
    tasks = tasks,
)

fun numericTask(
    title: String = "累计抽数",
    start: String = "0",
    target: String = "100",
    unit: String = "抽",
    recurrence: RecurrenceType = RecurrenceType.DAILY,
): TaskDraft = TaskDraft(
    title = title,
    progressType = ProgressType.NUMERIC,
    startValue = start,
    targetValue = target,
    unit = unit,
    recurrence = recurrence,
)

fun writeTestImage(context: Context, name: String = "shot.jpg", bytes: ByteArray = byteArrayOf(1, 2, 3, 4)): File {
    val directory = File(context.cacheDir, "test-images").apply { mkdirs() }
    return File(directory, name).apply { writeBytes(bytes) }
}

/**
 * A real, decodable JPEG. Manual probes seed this so the screenshot strip renders an
 * actual thumbnail instead of the "无法预览" fallback that four arbitrary bytes produce.
 */
fun writeTestJpeg(context: Context, name: String = "probe-checkin.jpg"): File {
    val directory = File(context.cacheDir, "test-images").apply { mkdirs() }
    val file = File(directory, name)
    val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(24, 92, 74))
        val paint = Paint().apply {
            color = Color.WHITE
            textSize = 44f
            isAntiAlias = true
        }
        canvas.drawText("手动验证截图", 48f, 140f, paint)
        canvas.drawText("游戏日期 2026-10-05", 48f, 220f, paint)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    } finally {
        bitmap.recycle()
    }
    return file
}
