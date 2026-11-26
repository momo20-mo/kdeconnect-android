/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.clipboard

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect.plugins.clipboard.ClipboardListener.ClipboardObserver
import org.kde.kdeconnect.ui.PluginSettingsFragment
import org.kde.kdeconnect_tp.R
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executor

@LoadablePlugin
class ClipboardPlugin : Plugin() {
    @VisibleForTesting
    internal var backgroundExecutor: Executor = Executor { ThreadHelper.runOnNewThread(it) }

    override val displayName: String
        get() = context.resources.getString(R.string.pref_plugin_clipboard)

    override val description: String
        get() = context.resources.getString(R.string.pref_plugin_clipboard_desc)

    override val hasSettings: Boolean = true

    override val settingsScope = SettingsScope.PER_DEVICE

    override fun getSettingsFragment(activity: Activity): PluginSettingsFragment {
        return PluginSettingsFragment.newInstance(pluginKey, device.deviceId, R.xml.clipboardplugin_preferences)
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        when (np.type) {
            PACKET_TYPE_CLIPBOARD -> {
                val content = np.getString("content")
                ClipboardListener.instance(context).setText(content)
                return true
            }
            PACKET_TYPE_CLIPBOARD_CONNECT -> {
                val packetTime = np.getLong("timestamp")
                // If the packetTime is 0, it means the timestamp is unknown (so do nothing).
                if (packetTime == 0L || packetTime < ClipboardListener.instance(context).updateTimestamp) {
                    return false
                }
                if ("content" in np) { // change clipboard if content is in NetworkPacket
                    val content = np.getString("content")
                    ClipboardListener.instance(context).setText(content)
                }
                return true
            }
            PACKET_TYPE_CLIPBOARD_FILE -> {
                return receiveClipboardImage(np)
            }
            else -> throw UnsupportedOperationException("Unknown packet type: ${np.type}")
        }
    }

    private val observer: ClipboardObserver = object : ClipboardObserver {
        override fun clipboardChanged(content: String, contentType: ClipboardListener.ClipboardContentType) {
            if (contentType == ClipboardListener.ClipboardContentType.Password &&
                preferences.getBoolean(
                    context.getString(R.string.clipboard_preference_key_skip_sensitive),
                    false,
                )
            ) {
                return
            }
            if (contentType == ClipboardListener.ClipboardContentType.Image) {
                sendClipboardImage(content.toUri())
                return
            }
            propagateClipboard(content)
        }
    }

    @VisibleForTesting
    fun propagateClipboard(content: String) {
        val np = NetworkPacket(PACKET_TYPE_CLIPBOARD)
        np["content"] = content
        device.sendPacket(np)
    }

    private fun isImageSyncEnabled(): Boolean {
        return preferences.getBoolean(
            context.getString(R.string.clipboard_preference_key_image_size_limit_enabled),
            true,
        )
    }

    private fun getMaxImageSizeMB(): Int {
        val key = context.getString(R.string.clipboard_preference_key_max_image_size_mb)
        return try {
            preferences.getInt(key, DEFAULT_MAX_IMAGE_SIZE_MB)
        } catch (_: ClassCastException) {
            preferences.getString(key, null)?.trim()?.toIntOrNull() ?: DEFAULT_MAX_IMAGE_SIZE_MB
        }.coerceAtLeast(0)
    }

    @VisibleForTesting
    internal fun sendClipboardImage(uri: Uri) {
        if (!isDeviceInitialized || !device.supportsPacketType(PACKET_TYPE_CLIPBOARD_FILE)) {
            return
        }
        if (!isImageSyncEnabled()) {
            return
        }
        val maxImageSizeMB = getMaxImageSizeMB()

        backgroundExecutor.execute {
            sendClipboardImageInternal(uri, maxImageSizeMB)
        }
    }

    private fun sendClipboardImageInternal(uri: Uri, maxImageSizeMB: Int) {
        if (!isDeviceInitialized || !device.supportsPacketType(PACKET_TYPE_CLIPBOARD_FILE)) {
            return
        }
        var bitmap: Bitmap? = null
        try {
            bitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
            if (bitmap == null) {
                Log.w(TAG, "Failed to decode clipboard image: $uri")
                return
            }

            val pngBytes = ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    Log.w(TAG, "Failed to compress clipboard image as PNG: $uri")
                    return
                }
                output.toByteArray()
            }

            if (pngBytes.isEmpty()) {
                return
            }

            if (maxImageSizeMB > 0 && pngBytes.size.toLong() > maxImageSizeMB.toLong() * ONE_MB) {
                Log.i(TAG, "Skipping clipboard image (${pngBytes.size} bytes): exceeds ${maxImageSizeMB}MB limit")
                return
            }

            val np = NetworkPacket(PACKET_TYPE_CLIPBOARD_FILE)
            np["filename"] = "${System.currentTimeMillis()}$CLIPBOARD_IMAGE_EXTENSION"
            np.payload = NetworkPacket.Payload(pngBytes)
            device.sendPacket(np)
        } catch (e: SecurityException) {
            Log.w(TAG, "Lost URI permission reading clipboard image: $uri", e)
        } catch (e: IOException) {
            Log.w(TAG, "I/O error reading clipboard image: $uri", e)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Out of memory processing clipboard image: $uri", e)
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error sending clipboard image: $uri", e)
        } finally {
            bitmap?.recycle()
        }
    }

    private fun receiveClipboardImage(np: NetworkPacket): Boolean {
        val payload = np.payload
        val filename = np.getStringOrNull("filename")
        if (payload == null || !np.hasPayload() || filename == null || !clipboardImageNameRegex.matches(filename)) {
            payload?.close()
            return false
        }

        backgroundExecutor.execute {
            receiveClipboardImageInternal(np, payload, filename)
        }
        return true
    }

    private fun receiveClipboardImageInternal(np: NetworkPacket, payload: NetworkPacket.Payload, filename: String) {
        val clipboardDir = File(context.cacheDir, CLIPBOARD_CACHE_DIR).apply { mkdirs() }
        val tempFile = createTempIncomingFile(clipboardDir)
        var promoted = false
        try {
            val inputStream = payload.inputStream
            if (inputStream == null) {
                Log.w(TAG, "Clipboard file packet has null payload stream: $filename")
                return
            }

            val received = FileOutputStream(tempFile).use { fileOut ->
                BufferedOutputStream(fileOut, TRANSFER_BUFFER_SIZE).use { bufferedOut ->
                    copyStream(inputStream, bufferedOut, np)
                }
            }

            if (np.isCanceled) {
                return
            }

            val expectedSize = np.payloadSize
            if (received <= 0L || (expectedSize >= 0L && received != expectedSize)) {
                Log.w(TAG, "Clipboard image size mismatch: received=$received expected=$expectedSize")
                return
            }

            if (!isValidPngFile(tempFile)) {
                Log.w(TAG, "Rejecting invalid PNG clipboard file: $filename")
                return
            }

            val finalFile = promoteIncomingImageFile(clipboardDir, tempFile, filename)
            promoted = true
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", finalFile)
            ClipboardListener.instance(context).setImage(uri, finalFile.name)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Out of memory validating received clipboard image: $filename", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to receive clipboard image: $filename", e)
        } finally {
            payload.close()
            if (!promoted) {
                removeTempIncomingFile(tempFile)
            }
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream, np: NetworkPacket): Long {
        val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
        var total = 0L
        while (!np.isCanceled) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            total += count.toLong()
        }
        output.flush()
        return total
    }

    private fun isValidPngFile(file: File): Boolean {
        if (!file.isFile || file.length() < PNG_SIGNATURE.size) {
            return false
        }
        val header = ByteArray(PNG_SIGNATURE.size)
        val headerRead = FileInputStream(file).use { stream ->
            var offset = 0
            while (offset < header.size) {
                val count = stream.read(header, offset, header.size - offset)
                if (count < 0) break
                offset += count
            }
            offset
        }
        if (headerRead != PNG_SIGNATURE.size || !header.contentEquals(PNG_SIGNATURE)) {
            return false
        }

        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return false
        return try {
            bitmap.width > 0 && bitmap.height > 0
        } finally {
            bitmap.recycle()
        }
    }

    private fun sendConnectPacket() {
        val listener = ClipboardListener.instance(context)
        val content = listener.currentContent ?: return // Send clipboard only if it had been initialized
        val contentType = listener.currentContentType
        if (contentType == ClipboardListener.ClipboardContentType.Image) {
            return
        }
        if (contentType == ClipboardListener.ClipboardContentType.Password &&
            preferences.getBoolean(
                context.getString(R.string.clipboard_preference_key_skip_sensitive),
                false,
            )
        ) {
            return
        }
        val np = NetworkPacket(PACKET_TYPE_CLIPBOARD_CONNECT)
        val timestamp = listener.updateTimestamp
        np["timestamp"] = timestamp
        np["content"] = content
        device.sendPacket(np)
    }


    override fun onCreate() {
        ClipboardListener.instance(context).registerObserver(observer)
        sendConnectPacket()
    }

    override fun onDestroy() {
        ClipboardListener.instance(context).removeObserver(observer)
    }

    override val supportedPacketTypes: Array<String> = arrayOf(
        PACKET_TYPE_CLIPBOARD,
        PACKET_TYPE_CLIPBOARD_CONNECT,
        PACKET_TYPE_CLIPBOARD_FILE,
    )

    override val outgoingPacketTypes: Array<String> = arrayOf(
        PACKET_TYPE_CLIPBOARD,
        PACKET_TYPE_CLIPBOARD_CONNECT,
        PACKET_TYPE_CLIPBOARD_FILE,
    )

    override fun getUiButtons(): List<PluginUiButton> {
        return if (!canSyncAutomatically(context)) {
            listOf(PluginUiButton(context.getString(R.string.send_clipboard), R.drawable.ic_baseline_content_paste_24) { _: Activity? ->
                userInitiatedSendClipboard()
            })
        } else {
            emptyList()
        }
    }

    override fun getUiMenuEntries(): List<PluginUiMenuEntry> {
        return if (canSyncAutomatically(context)) {
            // If automatic sync is supported, add the manual action less prominently, as a menu entry instead of a button.
            listOf(PluginUiMenuEntry(context.getString(R.string.send_clipboard)) { _: Activity? ->
                userInitiatedSendClipboard()
            })
        } else {
            emptyList()
        }
    }

    private fun userInitiatedSendClipboard() {
        if (isDeviceInitialized) {
            val clipboardManager = context.getSystemService<ClipboardManager>()!!
            if (clipboardManager.hasPrimaryClip()) {
                val clip = clipboardManager.primaryClip ?: return
                when (val parsed = ClipboardListener.parseClip(clip, context)) {
                    is ClipboardListener.ParsedClipboard.Image -> {
                        sendClipboardImage(parsed.uri)
                        Toast.makeText(this.context, R.string.pref_plugin_clipboard_sent, Toast.LENGTH_SHORT).show()
                    }
                    is ClipboardListener.ParsedClipboard.Text -> {
                        // Don't check if the content is sensitive, just send it
                        this.propagateClipboard(parsed.text)
                        Toast.makeText(this.context, R.string.pref_plugin_clipboard_sent, Toast.LENGTH_SHORT).show()
                    }
                    null -> Unit
                }
            }
        }
    }

    companion object {
        private const val TAG = "ClipboardPlugin"
        internal const val DEFAULT_MAX_IMAGE_SIZE_MB = 1
        private const val ONE_MB = 1024L * 1024L
        private const val TRANSFER_BUFFER_SIZE = 64 * 1024
        private const val CLIPBOARD_CACHE_DIR = "clipboard"
        private const val CLIPBOARD_IMAGE_EXTENSION = ".PNG"

        private val clipboardImageNameRegex = Regex("^[0-9]{10,17}\\.PNG$")
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )

        private val cacheFileLock = Any()
        private val activeIncomingFiles = mutableSetOf<File>()

        private fun createTempIncomingFile(clipboardDir: File): File = synchronized(cacheFileLock) {
            File.createTempFile("incoming-", ".tmp", clipboardDir).also { activeIncomingFiles.add(it) }
        }

        private fun removeTempIncomingFile(tempFile: File) = synchronized(cacheFileLock) {
            activeIncomingFiles.remove(tempFile)
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }

        private fun promoteIncomingImageFile(clipboardDir: File, tempFile: File, filename: String): File =
            synchronized(cacheFileLock) {
                val targetFile = File(clipboardDir, filename)
                clipboardDir.listFiles()?.forEach { existing ->
                    if (existing != tempFile && existing != targetFile && existing !in activeIncomingFiles) {
                        existing.delete()
                    }
                }
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
                activeIncomingFiles.remove(tempFile)
                targetFile
            }

        /**
         * Packet containing just clipboard contents, sent when a device updates its clipboard.
         *
         *
         * The body should look like so:
         * {
         * "content": "password"
         * }
         */
        private const val PACKET_TYPE_CLIPBOARD = "kdeconnect.clipboard"

        /**
         * Packet containing clipboard contents and a timestamp that the contents were last updated, sent
         * on first connection
         *
         *
         * The timestamp is milliseconds since epoch. It can be 0, which indicates that the clipboard
         * update time is currently unknown.
         *
         *
         * The body should look like so:
         * {
         * "timestamp": 542904563213,
         * "content": "password"
         * }
         */
        private const val PACKET_TYPE_CLIPBOARD_CONNECT = "kdeconnect.clipboard.connect"

        /**
         * Packet containing a clipboard file/image payload with a `filename` field in the body.
         */
        internal const val PACKET_TYPE_CLIPBOARD_FILE = "kdeconnect.clipboard.file"

        fun canSyncAutomatically(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return true
            }
            // Check if hack to read clipboard changes from logs is enabled
            return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
        }

    }
}
