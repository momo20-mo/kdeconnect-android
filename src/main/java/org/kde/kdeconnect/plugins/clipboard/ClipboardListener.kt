/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 * SPDX-FileCopyrightText: 2021 Ilmaz Gumerov <ilmaz1309@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.getSystemService
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect_tp.BuildConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClipboardListener {
    enum class ClipboardContentType {
        Text,
        Password,
        Image,
    }

    sealed class ParsedClipboard {
        data class Text(val text: String, val contentType: ClipboardContentType) : ParsedClipboard()
        data class Image(val uri: Uri) : ParsedClipboard()
    }

    interface ClipboardObserver {
        fun clipboardChanged(content: String, contentType: ClipboardContentType)
    }

    private val observers: HashSet<ClipboardObserver> = HashSet()

    private val context: Context
    private val mainHandler = Handler(Looper.getMainLooper())
    var currentContent: String? = null
        private set
    var currentContentType: ClipboardContentType = ClipboardContentType.Text
        private set
    var updateTimestamp: Long = 0
        private set

    private lateinit var cm: ClipboardManager

    private constructor(ctx: Context) {
        context = ctx.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            initClipboardManager()
        } else {
            mainHandler.post { initClipboardManager() }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ClipboardPlugin.canSyncAutomatically(context)) {
            ThreadHelper.runOnNewThread {
                try {
                    val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                    // Listen only ClipboardService errors after now
                    val logcatFilter = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.VANILLA_ICE_CREAM) { "E ClipboardService" } else { "ClipboardService:E" }
                    val process = Runtime.getRuntime().exec(arrayOf<String>("logcat", "-T", timeStamp, logcatFilter, "*:S"))
                    val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))
                    bufferedReader.forEachLine { line ->
                        if (line.contains(BuildConfig.APPLICATION_ID)) {
                            context.startActivity(ClipboardFloatingActivity.getIntent(context, false))
                        }
                    }
                } catch (_: Exception) { }
            }
        }
    }

    private fun initClipboardManager() {
        if (this::cm.isInitialized) {
            return
        }
        cm = context.getSystemService<ClipboardManager>()!!
        cm.addPrimaryClipChangedListener { this.onClipboardChanged() }
    }

    fun registerObserver(observer: ClipboardObserver) {
        observers.add(observer)
    }

    fun removeObserver(observer: ClipboardObserver) {
        observers.remove(observer)
    }

    @Synchronized
    fun onClipboardChanged() {
        try {
            val clip = cm.primaryClip ?: return
            when (val parsed = parseClip(clip, context)) {
                is ParsedClipboard.Image -> {
                    val uriString = parsed.uri.toString()
                    if (uriString == currentContent) {
                        return
                    }
                    updateTimestamp = System.currentTimeMillis()
                    currentContent = uriString
                    currentContentType = ClipboardContentType.Image

                    for (observer in observers) {
                        observer.clipboardChanged(uriString, ClipboardContentType.Image)
                    }
                }
                is ParsedClipboard.Text -> {
                    if (parsed.text == currentContent && parsed.contentType == currentContentType) {
                        return
                    }
                    updateTimestamp = System.currentTimeMillis()
                    currentContent = parsed.text
                    currentContentType = parsed.contentType

                    for (observer in observers) {
                        observer.clipboardChanged(parsed.text, parsed.contentType)
                    }
                }
                null -> return
            }
        } catch (_: Exception) {
            //Probably clipboard was not readable
        }
    }

    @Synchronized
    fun setText(text: String?) {
        if (!this::cm.isInitialized) {
            mainHandler.post { setText(text) }
            return
        }
        if (text == currentContent) {
            return
        }
        updateTimestamp = System.currentTimeMillis()
        currentContent = text
        currentContentType = ClipboardContentType.Text
        try {
            cm.setPrimaryClip(ClipData.newPlainText(null, text))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write text clipboard", e)
        }
    }

    /**
     * Updates the cached content before writing [uri] to the system clipboard so the
     * resulting primary-clip callback is recognized as our own write and suppressed.
     */
    @Synchronized
    fun setImage(uri: Uri, label: String? = null) {
        if (!this::cm.isInitialized) {
            mainHandler.post { setImage(uri, label) }
            return
        }
        val uriString = uri.toString()
        if (uriString == currentContent) {
            return
        }
        updateTimestamp = System.currentTimeMillis()
        currentContent = uriString
        currentContentType = ClipboardContentType.Image
        try {
            cm.setPrimaryClip(ClipData.newUri(context.contentResolver, label, uri))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write image clipboard", e)
        }
    }

    companion object {
        private const val TAG = "ClipboardListener"
        private var _instance: ClipboardListener? = null

        fun instance(context: Context): ClipboardListener {
            // FIXME: The _instance we return won't be completely initialized yet since initialization happens on a new thread (why?)
            return _instance ?: ClipboardListener(context).also { _instance = it }
        }

        private fun isImageUri(uri: Uri, resolver: ContentResolver, fallback: Boolean): Boolean {
            val mime = try {
                resolver.getType(uri)
            } catch (_: Exception) {
                null
            }
            return mime?.startsWith("image/") ?: fallback
        }

        /**
         * Classifies [clip] into a single readable image attachment URI, plain/sensitive text,
         * or `null` for non-image file attachments that should not be coerced into text.
         */
        fun parseClip(clip: ClipData, context: Context): ParsedClipboard? {
            if (clip.itemCount <= 0) {
                return null
            }
            val items = (0 until clip.itemCount).map { clip.getItemAt(it) }
            val attachmentUris = items.mapNotNull { it.uri }
                .filter { it.scheme == ContentResolver.SCHEME_CONTENT || it.scheme == ContentResolver.SCHEME_FILE }
            if (attachmentUris.isNotEmpty()) {
                val hasImageMime = clip.description?.hasMimeType("image/*") == true
                val isImage = attachmentUris.size == 1 && isImageUri(
                    attachmentUris.first(),
                    context.contentResolver,
                    hasImageMime,
                )
                return if (isImage) ParsedClipboard.Image(attachmentUris.first()) else null
            }

            val firstItem = items.first()
            if (clip.description?.hasMimeType("image/*") == true &&
                firstItem.text == null &&
                firstItem.htmlText == null
            ) {
                return null
            }

            val content = firstItem.coerceToText(context).toString()
            val contentType = detectContentType(clip)
            return ParsedClipboard.Text(content, contentType)
        }

        fun detectContentType(clip: ClipData?): ClipboardContentType {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                return ClipboardContentType.Text
            }
            if (clip?.description?.extras
                    ?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
            ) {
                return ClipboardContentType.Password
            }
            return ClipboardContentType.Text
        }
    }
}
