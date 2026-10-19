package com.daigan.record.data.template

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A shipped template asset. [seedKey] is durable: once it has been installed the marker
 * row survives editing and deleting, so a later launch never resurrects or overwrites
 * the user's library.
 */
data class BuiltInTemplateDescriptor(
    val seedKey: String,
    val assetPath: String,
)

/** Only the reusable starter template ships; game-specific templates can be imported. */
object BuiltInTemplates {
    const val REVERSE_1999_KEY = "reverse-1999"
    const val WUTHERING_WAVES_KEY = "wuthering-waves"
    const val GENERIC_KEY = "generic"

    val ALL: List<BuiltInTemplateDescriptor> = listOf(
        BuiltInTemplateDescriptor(GENERIC_KEY, "templates/generic.json"),
    )
}

/** Reads a built-in template asset; injectable so JVM tests can supply their own bytes. */
fun interface BuiltInTemplateSource {
    suspend fun read(assetPath: String): ByteArray
}

class AssetBuiltInTemplateSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : BuiltInTemplateSource {
    override suspend fun read(assetPath: String): ByteArray = withContext(Dispatchers.IO) {
        context.assets.open(assetPath).use { it.readBytes() }
    }
}

/** Display-name rules shared by the importer and its tests. */
object TemplateNames {

    const val IMPORT_SUFFIX = "（导入）"

    fun normalize(name: String): String = name.trim().lowercase(Locale.ROOT)

    /**
     * Returns a game name that is not already used by another template.
     *
     * "Save as" must produce a library entry that cannot be confused with the original
     * or collide with it during a later import, so a same-name import gets an explicit
     * suffix. The name is truncated if needed to respect the stored length limit.
     */
    fun uniqueGameName(
        base: String,
        existingNames: Collection<String>,
        maxLength: Int = 40,
    ): String {
        val trimmed = base.trim().ifEmpty { "导入模板" }
        val taken = existingNames.map(::normalize).toSet()
        if (normalize(trimmed) !in taken) return trimmed
        var attempt = 1
        while (true) {
            val suffix = if (attempt == 1) IMPORT_SUFFIX else "（导入 $attempt）"
            val room = (maxLength - suffix.length).coerceAtLeast(1)
            val candidate = trimmed.take(room).trimEnd() + suffix
            if (normalize(candidate) !in taken) return candidate
            attempt += 1
        }
    }
}
