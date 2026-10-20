package com.kuankuan.daigan.data.template

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kuankuan.daigan.testing.OrderTestEnvironment
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Document-picker streams: a UTF-8 write/read round trip through the real
 * ContentResolver, the size guard, and the failure message for an unavailable document.
 * No storage permission is involved; the test uses a private file URI.
 */
@RunWith(AndroidJUnit4::class)
class TemplateFileExchangeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var env: OrderTestEnvironment
    private lateinit var files: File

    @Before
    fun setUp() {
        env = OrderTestEnvironment(context)
        files = File(context.cacheDir, "template-exchange").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        env.close()
        files.deleteRecursively()
    }

    @Test
    fun writeAndReadRoundTripPreservesChineseContent() = runBlocking {
        env.templateRepository.ensureBuiltInTemplates()
        val template = env.templateRepository.findAllSummaries().first().let {
            env.templateRepository.findTemplate(it.id)!!
        }
        val bytes = TemplateFileCodec.encode(template)
        val target = File(files, "重返未来-导出.json")
        val uri = Uri.fromFile(target)

        val repository = ContentResolverTemplateFileRepository(context)
        repository.write(uri, bytes)
        val read = repository.read(uri)

        assertArrayEquals(bytes, read)
        val decoded = TemplateFileCodec.decode(read)
        assertTrue(decoded is TemplateFileResult.Success)
        assertEquals(
            template.gameName,
            (decoded as TemplateFileResult.Success).content.draft.gameName,
        )
    }

    @Test
    fun aDocumentOverTheLimitFailsWithoutBufferingIt() = runBlocking {
        val target = File(files, "oversized.json")
        target.writeBytes(ByteArray(TemplateFileCodec.MAX_FILE_BYTES + 1024) { 'a'.code.toByte() })
        val repository = ContentResolverTemplateFileRepository(context)

        val error = runCatching {
            repository.read(Uri.fromFile(target))
        }.exceptionOrNull()

        assertTrue(error is TemplateFileIoException)
        assertTrue(error!!.message.orEmpty().contains("上限"))
    }

    @Test
    fun anUnavailableDocumentFailsClearly() = runBlocking {
        val repository = ContentResolverTemplateFileRepository(context)
        val missing = Uri.fromFile(File(files, "does-not-exist.json"))
        val error = runCatching { repository.read(missing) }.exceptionOrNull()
        assertTrue(error is TemplateFileIoException)
        assertTrue(error!!.message.orEmpty().contains("无法读取"))
    }

    @Test
    fun aMalformedDocumentIsRejectedByTheCodecAfterTheStreamIsRead() = runBlocking {
        val target = File(files, "broken.json")
        target.writeText("{ this is not json", Charsets.UTF_8)
        val repository = ContentResolverTemplateFileRepository(context)
        val bytes = repository.read(Uri.fromFile(target))
        val decoded = TemplateFileCodec.decode(bytes)
        assertTrue(decoded is TemplateFileResult.Failure)
    }
}
