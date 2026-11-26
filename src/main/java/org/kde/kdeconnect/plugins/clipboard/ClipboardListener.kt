/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 * SPDX-FileCopyrightText: 2021 Ilmaz Gumerov <ilmaz1309@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.clipboard

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import org.kde.kdeconnect_tp.BuildConfig
import rikka.shizuku.Shizuku
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
    var currentContent: String? = null
        private set
    var currentContentType: ClipboardContentType = ClipboardContentType.Text
        private set
    var updateTimestamp: Long = 0
        private set

    private lateinit var cm: ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var shizukuBindingInFlight = false

    @Volatile
    private var shizukuBinderReceived = false

    @Volatile
    private var shizukuPermissionRequestInFlight = false

    @Volatile
    private var shizukuService: IClipboardMonitorService? = null

    @Volatile
    private var readLogsWorker: Thread? = null

    @Volatile
    private var readLogsProcess: Process? = null

    private val privilegedCallback = object : IClipboardMonitorCallback.Stub() {
        override fun onClipboardTextChanged(text: String?, isSensitive: Boolean) {
            if (text.isNullOrEmpty()) return

            Log.d(TAG, "Shizuku monitor delivered clipboard text")

            mainHandler.post {
                onPrivilegedClipboardTextChanged(text, isSensitive)
            }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        shizukuBinderReceived = true
        Log.d(TAG, "Shizuku binder received")
        ensureClipboardMonitor()
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Shizuku user service connected: $name")
            shizukuBindingInFlight = false

            try {
                val remote = IClipboardMonitorService.Stub.asInterface(service)
                shizukuService = remote
                remote?.start(privilegedCallback)
                Log.d(TAG, "Requested remote clipboard monitor start")
                stopReadLogsListener()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed starting Shizuku clipboard monitor", t)
                shizukuService = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Shizuku user service disconnected: $name")
            shizukuService = null
            shizukuBindingInFlight = false
            ensureClipboardMonitor()
        }
    }

    private constructor(ctx: Context) {
        context = ctx.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            initClipboardManager()
        } else {
            mainHandler.post { initClipboardManager() }
        }
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Log.d(TAG, "Registered Shizuku binder listener")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to register Shizuku binder listener", t)
        }

        ensureClipboardMonitor()
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

    private fun hasReadLogsPermission(): Boolean {
        return Build.VERSION.SDK_INT > Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
    }

    private fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    private fun isShizukuAvailableAndAuthorized(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    private fun ensureClipboardMonitor() {
        val hasReadLogs = hasReadLogsPermission()
        val binderReceived = shizukuBinderReceived
        val shizukuAvailable = if (binderReceived) isShizukuAvailable() else false
        val shizukuAuthorized = if (binderReceived) isShizukuAvailableAndAuthorized() else false

        Log.d(
            TAG,
            "ensureClipboardMonitor: readLogs=$hasReadLogs binderReceived=$binderReceived shizukuAvailable=$shizukuAvailable shizukuAuthorized=$shizukuAuthorized"
        )

        if (shizukuAuthorized) {
            startShizukuClipboardMonitor()
            stopReadLogsListener()
            return
        }

        if (hasReadLogs) {
            startReadLogsListener()
        }

        if (!binderReceived) {
            Log.d(TAG, "Shizuku binder not received yet")
            return
        }

        if (shizukuAvailable) {
            requestShizukuPermission()
        } else {
            Log.d(TAG, "Shizuku binder unavailable")
        }
    }

    private fun requestShizukuPermission() {
        if (shizukuPermissionRequestInFlight) {
            Log.d(TAG, "Shizuku permission request already in flight")
            return
        }

        shizukuPermissionRequestInFlight = true
        Log.d(TAG, "Requesting Shizuku permission")

        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != SHIZUKU_PERMISSION_REQUEST_CODE) return

                Shizuku.removeRequestPermissionResultListener(this)
                shizukuPermissionRequestInFlight = false

                Log.d(TAG, "Shizuku permission result: $grantResult")

                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    startShizukuClipboardMonitor()
                    stopReadLogsListener()
                }
            }
        }

        Shizuku.addRequestPermissionResultListener(listener)
        try {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        } catch (t: Throwable) {
            Shizuku.removeRequestPermissionResultListener(listener)
            shizukuPermissionRequestInFlight = false
            Log.w(TAG, "Shizuku.requestPermission failed", t)
        }
    }

    private fun clipboardLogcatFilter(): String {
        return if (Build.VERSION.SDK_INT > Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            "E ClipboardService"
        } else {
            "ClipboardService:E"
        }
    }

    private fun clipboardLogcatSince(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    }

    private fun startReadLogsListener() {
        val existing = readLogsWorker
        if (existing != null && existing.isAlive) return

        readLogsWorker = Thread({
            var localProcess: Process? = null
            try {
                localProcess = Runtime.getRuntime().exec(
                    arrayOf("logcat", "-T", clipboardLogcatSince(), clipboardLogcatFilter(), "*:S")
                )
                readLogsProcess = localProcess

                BufferedReader(InputStreamReader(localProcess.inputStream)).useLines { lines ->
                    lines.forEach { line ->
                        if (line.contains(BuildConfig.APPLICATION_ID)) {
                            Log.d(TAG, "READ_LOGS fallback observed denial; launching floating activity")
                            mainHandler.post {
                                try {
                                    context.startActivity(ClipboardFloatingActivity.getIntent(context, false))
                                } catch (t: Throwable) {
                                    Log.w(TAG, "READ_LOGS fallback launch failed", t)
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "READ_LOGS fallback watcher failed", t)
            } finally {
                try {
                    localProcess?.destroy()
                } catch (_: Throwable) {
                }

                if (readLogsProcess === localProcess) {
                    readLogsProcess = null
                }
                readLogsWorker = null
            }
        }, "kdeconnect-clipboard-readlogs").apply {
            isDaemon = true
            start()
        }

        Log.d(TAG, "Started READ_LOGS clipboard fallback")
    }

    private fun stopReadLogsListener() {
        try {
            readLogsWorker?.interrupt()
        } catch (_: Throwable) {
        }

        try {
            readLogsProcess?.destroy()
        } catch (_: Throwable) {
        }

        readLogsWorker = null
        readLogsProcess = null
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs {
        return Shizuku.UserServiceArgs(
            ComponentName(context, ClipboardMonitorUserService::class.java)
        )
            .daemon(false)
            .tag("clipboard_monitor_v4")
            .processNameSuffix("clipboard_monitor_v4")
            .version(4)
            .debuggable(BuildConfig.DEBUG)
    }

    private fun startShizukuClipboardMonitor() {
        if (shizukuService != null || shizukuBindingInFlight) {
            Log.d(TAG, "Shizuku clipboard monitor already started/binding")
            return
        }

        shizukuBindingInFlight = true
        Log.d(TAG, "Binding Shizuku user service")

        try {
            Shizuku.bindUserService(userServiceArgs(), userServiceConnection)
        } catch (t: Throwable) {
            shizukuBindingInFlight = false
            Log.w(TAG, "Shizuku.bindUserService failed", t)
        }
    }

    @Synchronized
    private fun onPrivilegedClipboardTextChanged(text: String, isSensitive: Boolean) {
        // If Shizuku delivered a content:// URI, it represents an image clipboard entry.
        // clipToImageUri() in ClipboardMonitorUserService returns such URIs for image clips.
        val imageUri = text.takeIf { it.startsWith("content://") || it.startsWith("file://") }
            ?.let {
                try {
                    Uri.parse(it)
                } catch (_: Exception) {
                    null
                }
            }

        if (imageUri != null) {
            if (text == currentContent) return

            Log.d(TAG, "Shizuku detected clipboard image; launching floating activity")

            mainHandler.post {
                try {
                    context.startActivity(
                        ClipboardFloatingActivity.getIntent(context, false)
                    )
                    Log.d(TAG, "Shizuku clipboard image: floating activity launched")
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to launch floating activity for clipboard image", t)
                }
            }
            return
        }

        val contentType = if (isSensitive) {
            ClipboardContentType.Password
        } else {
            ClipboardContentType.Text
        }
        if (text == currentContent && (contentType == currentContentType || currentContentType == ClipboardContentType.Image)) {
            return
        }

        updateTimestamp = System.currentTimeMillis()
        currentContent = text
        currentContentType = contentType

        val snapshot = observers.toList()
        for (observer in snapshot) {
            observer.clipboardChanged(text, contentType)
        }
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
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
        private var _instance: ClipboardListener? = null

        fun instance(context: Context): ClipboardListener {
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
