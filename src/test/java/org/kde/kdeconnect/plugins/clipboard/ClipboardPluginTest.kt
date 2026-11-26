package org.kde.kdeconnect.plugins.clipboard

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Looper
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.MockSharedPreference
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect_tp.R
import org.robolectric.Shadows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class ClipboardPluginTest {
    private lateinit var clipboardPlugin: ClipboardPlugin
    private lateinit var clipboardListener: ClipboardListener
    private lateinit var context: Application
    private lateinit var device: Device
    private lateinit var preferences: MockSharedPreference
    private var packet: NetworkPacket? = null

    private companion object {
        const val SKIP_SENSITIVE_PREF_KEY = "clipboard_skip_sensitive_sync"
        const val IMAGE_SIZE_LIMIT_ENABLED_KEY = "clipboard_image_size_limit_enabled"
        const val MAX_IMAGE_SIZE_MB_KEY = "maxClipboardImageSizeMB"
        const val PREFERENCES_NAME = "testDevice_ClipboardPlugin_preferences"
    }

    @Before
    fun setup() {
        preferences = MockSharedPreference()
        context = spyk(ApplicationProvider.getApplicationContext()) {
            every { getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE) } returns preferences
            every { getString(R.string.clipboard_preference_key_skip_sensitive) } returns SKIP_SENSITIVE_PREF_KEY
        }

        val realListener = ClipboardListener.instance(context)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        clipboardListener = spyk(realListener)

        mockkObject(ClipboardListener.Companion)
        every { ClipboardListener.instance(context) } returns clipboardListener

        device = mockk {
            every { deviceId } returns "testDevice"
            every { supportsPacketType(any()) } returns true
            every { sendPacket(any()) } answers {
                packet = arg<NetworkPacket>(0)
            }
        }

        clipboardPlugin = ClipboardPlugin().also {
            it.backgroundExecutor = Executor { runnable -> runnable.run() }
            it.setContext(context, device)
        }
    }

    @After
    fun cleanup() {
        packet = null // Remove old capture packet
        preferences.edit().clear().apply()
        File(context.cacheDir, "clipboard").deleteRecursively()
        unmockkAll()
    }

    private fun createTestPngBytes(width: Int = 8, height: Int = 8): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { output ->
            Assert.assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
            output.toByteArray()
        }
    }

    // REMOTE -> LOCAL

    @Test
    fun testReceiveAndApplyClipboardUpdate() {
        val content = "Gr5hDjL68YQKfH6pez7n59Dm6"
        val packet = NetworkPacket("kdeconnect.clipboard").apply {
            set("content", content)
        }

        Assert.assertTrue(clipboardPlugin.onPacketReceived(packet))

        // Verify that the clipboard content has been set
        verify(exactly = 1) { clipboardListener.setText(content) }
    }

    @Test
    fun testReceiveAndDiscardClipboardUpdateTimestampZero() {
        val content = "DLWq7RvblSa6zFPrwLjs9JAdA"
        val timestamp = System.currentTimeMillis()
        val packet = NetworkPacket("kdeconnect.clipboard.connect").apply {
            set("content", content)
            set("timestamp", timestamp)
        }

        every { clipboardListener.updateTimestamp } returns 0L // Existing timestamp is invalid

        Assert.assertTrue(clipboardPlugin.onPacketReceived(packet))

        // Verify that the clipboard content is updated
        verify(exactly = 1) { clipboardListener.setText(content) }
    }

    @Test
    fun testReceiveAndDiscardClipboardUpdateTimestampOld() {
        val content = "2aZB2x22brdYSubSPPDr864LW"
        val timestamp = System.currentTimeMillis() - 1000 // Simulating an older timestamp
        val packet = NetworkPacket("kdeconnect.clipboard.connect").apply {
            set("content", content)
            set("timestamp", timestamp)
        }

        every { clipboardListener.updateTimestamp } returns timestamp + 1000

        Assert.assertFalse(clipboardPlugin.onPacketReceived(packet))

        // Verify that the clipboard content is NOT updated
        verify(exactly = 0) { clipboardListener.setText(content) }
    }

    @Test(expected = UnsupportedOperationException::class)
    fun testReceiveInvalidPacket() {
        val invalidPacket = NetworkPacket("invalid.type")
        clipboardPlugin.onPacketReceived(invalidPacket) // Should throw exception
    }

    // LOCAL -> REMOTE

    @Test
    fun testOnCreate() {
        val content = "B7n30xe0NNO6Y1J7PXOFj6pGd"
        every { clipboardListener.currentContent } returns content
        every { clipboardListener.currentContentType } returns ClipboardListener.ClipboardContentType.Text

        clipboardPlugin.onCreate()

        val sentPacket = checkNotNull(packet)
        Assert.assertEquals("kdeconnect.clipboard.connect", sentPacket.type)
        Assert.assertEquals(0, sentPacket.getLong("timestamp"))
        Assert.assertEquals(content, sentPacket.getString("content"))
    }

    @Test
    fun testSendConnectPacketSkippedWhenClipSensitiveAndPreferenceOn() {
        preferences.edit().putBoolean(SKIP_SENSITIVE_PREF_KEY, true).apply()

        every { clipboardListener.currentContent } returns "secret"
        every { clipboardListener.currentContentType } returns ClipboardListener.ClipboardContentType.Password
        every { clipboardListener.updateTimestamp } returns 12345L

        clipboardPlugin.onCreate()

        Assert.assertNull(packet)
        verify(exactly = 0) { device.sendPacket(any()) }
    }

    @Test
    fun testSendConnectPacketSentWhenClipSensitiveButPreferenceOff() {
        preferences.edit().putBoolean(SKIP_SENSITIVE_PREF_KEY, false).apply()

        val content = "still-sent"
        every { clipboardListener.currentContent } returns content
        every { clipboardListener.currentContentType } returns ClipboardListener.ClipboardContentType.Password
        every { clipboardListener.updateTimestamp } returns 999L

        clipboardPlugin.onCreate()

        val sentPacket = checkNotNull(packet)
        Assert.assertEquals("kdeconnect.clipboard.connect", sentPacket.type)
        Assert.assertEquals(content, sentPacket.getString("content"))
    }

    @Test
    fun testPropagateClipboard() {
        val content = "llY3kfZNhPur9ldTWEuHQBHPC"
        every { clipboardListener.currentContent } returns content

        clipboardPlugin.propagateClipboard(content)

        val sentPacket = checkNotNull(packet)
        Assert.assertEquals("kdeconnect.clipboard", sentPacket.type)
        Assert.assertEquals(content, sentPacket.getString("content"))
    }

    @Test
    fun testOnCreateSkipsImageContent() {
        every { clipboardListener.currentContent } returns "content://org.kde.kdeconnect_tp.fileprovider/clipboard_images/1764000000000.PNG"
        every { clipboardListener.currentContentType } returns ClipboardListener.ClipboardContentType.Image

        clipboardPlugin.onCreate()

        Assert.assertNull(packet)
        verify(exactly = 0) { device.sendPacket(any()) }
    }

    @Test
    fun testReceiveClipboardImageAcceptsTimestampPngAndCleansOldCache() {
        val clipboardDir = File(context.cacheDir, "clipboard").apply { mkdirs() }
        val staleFile = File(clipboardDir, "1700000000000.PNG").apply { writeText("stale") }

        val pngBytes = createTestPngBytes()
        val filename = "1764000000000.PNG"
        val incoming = NetworkPacket(ClipboardPlugin.PACKET_TYPE_CLIPBOARD_FILE).apply {
            set("filename", filename)
            payload = NetworkPacket.Payload(pngBytes)
        }

        Assert.assertTrue(clipboardPlugin.onPacketReceived(incoming))

        val savedFile = File(clipboardDir, filename)
        Assert.assertTrue(savedFile.exists())
        Assert.assertFalse(staleFile.exists())
        Assert.assertArrayEquals(pngBytes, savedFile.readBytes())

        verify(exactly = 1) {
            clipboardListener.setImage(
                match { it.toString().endsWith("/clipboard_images/$filename") },
                filename,
            )
        }
        Assert.assertEquals(ClipboardListener.ClipboardContentType.Image, clipboardListener.currentContentType)
        Assert.assertTrue(clipboardListener.currentContent?.endsWith("/clipboard_images/$filename") == true)
    }

    @Test
    fun testReceiveClipboardFileRejectsNonImageNamesWithoutDownloading() {
        val rejectedNames = listOf("archive.zip", "photo.png", "1764000000000.png", "test.PNG", "12345.PNG")
        for (name in rejectedNames) {
            val payloadStream = spyk(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
            val incoming = NetworkPacket(ClipboardPlugin.PACKET_TYPE_CLIPBOARD_FILE).apply {
                set("filename", name)
                payload = NetworkPacket.Payload(payloadStream, 3L)
            }

            Assert.assertFalse("Expected rejection for $name", clipboardPlugin.onPacketReceived(incoming))
            verify(exactly = 0) { payloadStream.read(any<ByteArray>()) }
            verify(exactly = 1) { payloadStream.close() }
        }

        verify(exactly = 0) { clipboardListener.setImage(any(), any()) }
        Assert.assertTrue(File(context.cacheDir, "clipboard").listFiles().isNullOrEmpty())
    }

    @Test
    fun testReceiveClipboardImageRejectsInvalidPngPayload() {
        val filename = "1764000000001.PNG"
        val invalidBytes = "not-a-png-file".toByteArray()
        val incoming = NetworkPacket(ClipboardPlugin.PACKET_TYPE_CLIPBOARD_FILE).apply {
            set("filename", filename)
            payload = NetworkPacket.Payload(invalidBytes)
        }

        Assert.assertTrue(clipboardPlugin.onPacketReceived(incoming))
        verify(exactly = 0) { clipboardListener.setImage(any(), any()) }
        Assert.assertFalse(File(context.cacheDir, filename).exists())
    }

    @Test
    fun testSendClipboardImageEncodesPngAndSetsTimestampFilename() {
        val sourceFile = File(context.cacheDir, "source_image.png").apply {
            writeBytes(createTestPngBytes(12, 12))
        }
        clipboardPlugin.sendClipboardImage(Uri.fromFile(sourceFile))

        val sentPacket = checkNotNull(packet)
        Assert.assertEquals(ClipboardPlugin.PACKET_TYPE_CLIPBOARD_FILE, sentPacket.type)
        val sentFilename = sentPacket.getString("filename")
        Assert.assertTrue(Regex("^[0-9]{10,17}\\.PNG$").matches(sentFilename))
        val payloadBytes = sentPacket.payload?.inputStream?.readBytes()
        Assert.assertNotNull(payloadBytes)
        val decoded = BitmapFactory.decodeByteArray(payloadBytes, 0, payloadBytes!!.size)
        Assert.assertNotNull(decoded)
        decoded.recycle()
    }

    @Test
    fun testSendClipboardImageDisabledWhenMaxSizeIsZero() {
        preferences.edit()
            .putBoolean(IMAGE_SIZE_LIMIT_ENABLED_KEY, true)
            .putInt(MAX_IMAGE_SIZE_MB_KEY, 0)
            .apply()

        val sourceFile = File(context.cacheDir, "zero_limit_image.png").apply {
            writeBytes(createTestPngBytes())
        }
        clipboardPlugin.sendClipboardImage(Uri.fromFile(sourceFile))

        Assert.assertNull(packet)
        verify(exactly = 0) { device.sendPacket(any()) }
    }

    @Test
    fun testSendClipboardImageRespectsSizeLimitToggle() {
        // 0 MB blocks sending when limit is enabled, but disabling the limit allows sending
        preferences.edit()
            .putBoolean(IMAGE_SIZE_LIMIT_ENABLED_KEY, false)
            .putInt(MAX_IMAGE_SIZE_MB_KEY, 0)
            .apply()

        val sourceFile = File(context.cacheDir, "unlimited_image.png").apply {
            writeBytes(createTestPngBytes())
        }
        clipboardPlugin.sendClipboardImage(Uri.fromFile(sourceFile))

        val sentPacket = checkNotNull(packet)
        Assert.assertEquals(ClipboardPlugin.PACKET_TYPE_CLIPBOARD_FILE, sentPacket.type)
    }

    @Test
    fun testSetImageUpdatesCacheBeforePrimaryClipAndSuppressesEcho() {
        val clipboardDir = File(context.cacheDir, "clipboard").apply { mkdirs() }
        val file = File(clipboardDir, "1764000000002.PNG").apply { writeBytes(createTestPngBytes()) }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        val observer = mockk<ClipboardListener.ClipboardObserver>(relaxed = true)
        clipboardListener.registerObserver(observer)
        try {
            clipboardListener.setImage(uri, file.name)
            Assert.assertEquals(uri.toString(), clipboardListener.currentContent)
            Assert.assertEquals(ClipboardListener.ClipboardContentType.Image, clipboardListener.currentContentType)

            clipboardListener.onClipboardChanged()
            verify(exactly = 0) { observer.clipboardChanged(any(), any()) }
        } finally {
            clipboardListener.removeObserver(observer)
        }
    }

    @Test
    fun testParseClipClassifiesImageFileAndWebUrlCorrectly() {
        val imageClip = ClipData.newRawUri("img", Uri.parse("content://example.provider/1.png")).apply {
            description.extras
        }
        val imageClipWithMime = ClipData(
            "img",
            arrayOf("image/png"),
            ClipData.Item(Uri.parse("content://example.provider/1.png")),
        )
        Assert.assertEquals(
            ClipboardListener.ParsedClipboard.Image(Uri.parse("content://example.provider/1.png")),
            ClipboardListener.parseClip(imageClipWithMime, context),
        )

        val zipClip = ClipData(
            "zip",
            arrayOf("application/zip"),
            ClipData.Item(Uri.parse("content://example.provider/archive.zip")),
        )
        Assert.assertNull(ClipboardListener.parseClip(zipClip, context))

        val urlClip = ClipData.newPlainText(null, "https://kde.org")
        Assert.assertEquals(
            ClipboardListener.ParsedClipboard.Text("https://kde.org", ClipboardListener.ClipboardContentType.Text),
            ClipboardListener.parseClip(urlClip, context),
        )
        // Keep unused variable check clean
        Assert.assertNull(ClipboardListener.parseClip(imageClip, context))
    }
}
