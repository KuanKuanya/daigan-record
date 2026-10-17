package com.daigan.record.data.local.converter

import androidx.room.TypeConverter
import com.daigan.record.data.model.LoginMethod
import java.time.Instant

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
