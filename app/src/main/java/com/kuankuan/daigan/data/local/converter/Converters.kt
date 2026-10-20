package com.kuankuan.daigan.data.local.converter

import androidx.room.TypeConverter
import com.kuankuan.daigan.data.model.BatchItemType
import com.kuankuan.daigan.data.model.BatchScope
import com.kuankuan.daigan.data.model.LoginMethod
import com.kuankuan.daigan.data.model.OrderType
import com.kuankuan.daigan.data.model.PaymentMode
import com.kuankuan.daigan.data.model.ProgressType
import com.kuankuan.daigan.data.model.RecurrenceType
import com.kuankuan.daigan.data.model.TemplateOrigin
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Stores event timestamps as epoch milliseconds (UTC) and reads them back as [Instant]. */
class InstantConverters {
    @TypeConverter
    fun fromInstant(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun toInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)
}

/** Stores the login method by name so the schema stays readable and stable. */
class LoginMethodConverters {
    @TypeConverter
    fun fromLoginMethod(value: LoginMethod): String = value.name

    @TypeConverter
    fun toLoginMethod(value: String): LoginMethod = LoginMethod.valueOf(value)
}

/** Game dates and period dates are stored as ISO-8601 text (sortable and readable). */
class LocalDateConverters {
    @TypeConverter
    fun fromLocalDate(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    fun toLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)
}

/**
 * Exact decimal progress. TEXT avoids binary floating point and preserves the value the
 * user typed (e.g. "1.25" for a fractional unit).
 */
class BigDecimalConverters {
    @TypeConverter
    fun fromBigDecimal(value: BigDecimal?): String? = value?.toPlainString()

    @TypeConverter
    fun toBigDecimal(value: String?): BigDecimal? = value?.let(::BigDecimal)
}

/** Enum values are persisted by name; adding a value is a schema-visible change. */
class OrderEnumConverters {
    @TypeConverter
    fun fromOrderType(value: OrderType): String = value.name

    @TypeConverter
    fun toOrderType(value: String): OrderType = OrderType.valueOf(value)

    @TypeConverter
    fun fromPaymentMode(value: PaymentMode): String = value.name

    @TypeConverter
    fun toPaymentMode(value: String): PaymentMode = PaymentMode.valueOf(value)

    @TypeConverter
    fun fromProgressType(value: ProgressType): String = value.name

    @TypeConverter
    fun toProgressType(value: String): ProgressType = ProgressType.valueOf(value)

    @TypeConverter
    fun fromRecurrenceType(value: RecurrenceType): String = value.name

    @TypeConverter
    fun toRecurrenceType(value: String): RecurrenceType = RecurrenceType.valueOf(value)

    @TypeConverter
    fun fromBatchScope(value: BatchScope): String = value.name

    @TypeConverter
    fun toBatchScope(value: String): BatchScope = BatchScope.valueOf(value)

    @TypeConverter
    fun fromBatchItemType(value: BatchItemType): String = value.name

    @TypeConverter
    fun toBatchItemType(value: String): BatchItemType = BatchItemType.valueOf(value)

    @TypeConverter
    fun fromTemplateOrigin(value: TemplateOrigin): String = value.name

    @TypeConverter
    fun toTemplateOrigin(value: String): TemplateOrigin = TemplateOrigin.valueOf(value)
}
