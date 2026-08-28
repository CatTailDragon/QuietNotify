package io.github.lsp1.quietnotify.xposed

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import io.github.lsp1.quietnotify.Config
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class QuietNotifyModule : XposedModule() {
    private val soundTracker = WindowTracker()
    private val headsUpTracker = WindowTracker()
    @Volatile private var enabled = true
    @Volatile private var headsUpEnabled = true
    @Volatile private var soundEnabled = true
    @Volatile private var rules: Map<String, Long> = emptyMap()
    private var preferences: SharedPreferences? = null
    private var recordAccess: RecordAccess? = null
    private var systemUiContext: Context? = null
    private val systemServerInstalled = AtomicBoolean()
    private val systemUiInstalled = AtomicBoolean()
    private val diagnosticLogsRemaining = AtomicInteger(20)
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
        reloadRules(prefs)
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "Loaded in ${param.processName}, API $apiVersion")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        if (!isSupportedAndroid()) return
        if (!systemServerInstalled.compareAndSet(false, true)) return

        runCatching {
            initializePreferences()
            installSystemServerNotificationHook(param.classLoader)
        }.onFailure {
            systemServerInstalled.set(false)
            log(Log.ERROR, TAG, "Failed to initialize system_server notification hook", it)
        }
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != SYSTEM_UI_PACKAGE || !param.isFirstPackage) return
        if (!isSupportedAndroid()) return
        if (!systemUiInstalled.compareAndSet(false, true)) return

        runCatching {
            initializePreferences()
            installSystemUiHeadsUpHook(param.defaultClassLoader)
        }.onFailure {
            systemUiInstalled.set(false)
            log(Log.ERROR, TAG, "Failed to initialize SystemUI heads-up hook", it)
        }
    }

    private fun isSupportedAndroid(): Boolean {
        if (Build.VERSION.SDK_INT in 34..36) return true
        log(Log.WARN, TAG, "Unsupported Android API ${Build.VERSION.SDK_INT}; hook skipped")
        return false
    }

    private fun initializePreferences() {
        if (preferences != null) return
        getRemotePreferences(Config.GROUP).also { prefs ->
            preferences = prefs
            reloadRules(prefs)
            prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        }
    }

    private fun installSystemServerNotificationHook(classLoader: ClassLoader) {
        val candidates = listOf(
            "com.android.server.notification.NotificationAttentionHelper",
            "com.android.server.notification.NotificationManagerService",
        )
        val recordClass = Class.forName(
            "com.android.server.notification.NotificationRecord",
            false,
            classLoader,
        )
        recordAccess = RecordAccess(recordClass)
        val candidateClasses = candidates.mapNotNull { name ->
            runCatching { Class.forName(name, false, classLoader) }.getOrNull()
        }
        val attentionMethods = candidateClasses.asSequence()
            .flatMap { type -> type.declaredMethods.asSequence() }
            .filter { method ->
                method.name == "buzzBeepBlinkLocked" &&
                    method.returnType == Int::class.javaPrimitiveType &&
                    method.parameterTypes.firstOrNull() == recordClass
            }
            .distinctBy(Method::toGenericString)
            .toList()

        if (attentionMethods.isNotEmpty()) {
            attentionMethods.forEach { method ->
                hook(method).setPriority(PRIORITY_HIGHEST).intercept { chain ->
                    val record = chain.args[0]
                    val mute = shouldMuteByRule(record)
                    logDiagnostic(record, method, mute)
                    if (mute) {
                        recordAccess!!.muteDuring(record) { chain.proceed() }
                    } else {
                        chain.proceed()
                    }
                }
                log(Log.INFO, TAG, "Hooked ${method.declaringClass.simpleName}.${method.name}/${method.parameterCount}")
            }
            return
        }

        // Some ROMs retain the AOSP mute decision method but rename the attention method.
        val muteMethods = candidateClasses.asSequence()
            .flatMap { type -> type.declaredMethods.asSequence() }
            .filter { method ->
                method.name == "shouldMuteNotificationLocked" &&
                    method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes.firstOrNull() == recordClass
            }
            .distinctBy(Method::toGenericString)
            .toList()

        check(muteMethods.isNotEmpty()) { "No compatible notification attention method" }
        muteMethods.forEach { method ->
            hook(method).intercept { chain ->
                val systemMuted = chain.proceed() as Boolean
                val mute = !systemMuted && shouldMuteByRule(chain.args[0])
                logDiagnostic(chain.args[0], method, mute, systemMuted)
                systemMuted || mute
            }
            log(Log.INFO, TAG, "Hooked ${method.declaringClass.simpleName}.${method.name}/${method.parameterCount}")
        }
    }

    private fun installSystemUiHeadsUpHook(classLoader: ClassLoader) {
        val entryClass = Class.forName(NOTIFICATION_ENTRY_CLASS, false, classLoader)
        val entryAccess = EntryAccess(entryClass)
        val decisionMethods = HEADS_UP_PROVIDER_CLASSES.flatMap { name ->
            runCatching { Class.forName(name, false, classLoader) }.getOrNull()
                ?.let { type ->
                    type.declaredMethods.filter { method ->
                        method.name == "makeAndLogHeadsUpDecision" &&
                            method.parameterTypes.contentEquals(arrayOf(entryClass)) &&
                            method.returnType.isInterface &&
                            method.returnType.methods.any {
                                it.name == "getShouldInterrupt" && it.parameterCount == 0
                            }
                    }
                }.orEmpty()
        }.distinctBy(Method::toGenericString)

        decisionMethods.forEach { method ->
            hook(method).setPriority(PRIORITY_HIGHEST).intercept { chain ->
                val decision = chain.proceed() ?: return@intercept null
                val shouldInterrupt = runCatching {
                    method.returnType.getMethod("getShouldInterrupt").invoke(decision) as Boolean
                }.getOrDefault(false)
                if (!shouldInterrupt || !shouldSuppressHeadsUp(entryAccess, chain.args[0])) {
                    return@intercept decision
                }
                suppressDecision(method.returnType, decision)
            }
            log(Log.INFO, TAG, "Hooked ${method.declaringClass.simpleName}.${method.name}/${method.parameterCount}")
        }

        val legacyMethods = if (decisionMethods.isEmpty()) {
            runCatching {
                Class.forName(LEGACY_INTERRUPT_PROVIDER_CLASS, false, classLoader)
            }.getOrNull()?.declaredMethods.orEmpty().filter { method ->
                method.name == "shouldHeadsUpWhenAwake" &&
                    method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes.contentEquals(
                        arrayOf(entryClass, Boolean::class.javaPrimitiveType),
                    )
            }
        } else {
            emptyList()
        }
        legacyMethods.forEach { method ->
            hook(method).setPriority(PRIORITY_LOWEST).intercept { chain ->
                val systemAllows = chain.proceed() as Boolean
                val loggedDecision = chain.args.getOrNull(1) == true
                systemAllows && !(loggedDecision && shouldSuppressHeadsUp(entryAccess, chain.args[0]))
            }
            log(Log.INFO, TAG, "Hooked ${method.declaringClass.simpleName}.${method.name}/${method.parameterCount}")
        }

        val bindingMethods = if (decisionMethods.isEmpty() && legacyMethods.isEmpty()) {
            installSystemUiBindingFallback(classLoader, entryClass, entryAccess)
        } else {
            emptyList()
        }

        check(decisionMethods.isNotEmpty() || legacyMethods.isNotEmpty() || bindingMethods.isNotEmpty()) {
            "No compatible SystemUI heads-up decision method"
        }
    }

    private fun installSystemUiBindingFallback(
        classLoader: ClassLoader,
        entryClass: Class<*>,
        entryAccess: EntryAccess,
    ): List<Method> {
        val coordinatorClass = runCatching {
            Class.forName(HEADS_UP_COORDINATOR_CLASS, false, classLoader)
        }.getOrNull()
        val coordinatorMethods = coordinatorClass?.declaredMethods.orEmpty().filter { method ->
            method.name == "bindForAsyncHeadsUp" &&
                method.returnType == Void.TYPE &&
                method.parameterCount == 1
        }.mapNotNull { method ->
            val postedAccess = runCatching {
                PostedEntryAccess(method.parameterTypes[0], entryClass)
            }.getOrNull() ?: return@mapNotNull null
            hook(method).setPriority(PRIORITY_HIGHEST).intercept { chain ->
                val entry = postedAccess.getEntry(chain.args[0])
                if (shouldSuppressHeadsUp(entryAccess, entry)) null else chain.proceed()
            }
            log(Log.INFO, TAG, "Hooked ${method.declaringClass.simpleName}.${method.name}/1")
            method
        }
        if (coordinatorMethods.isNotEmpty()) return coordinatorMethods

        // This is less ideal than the coordinator entry because its short-lived binding marker
        // remains until SystemUI's normal timeout, but no heads-up view has been bound yet.
        val binderMethods = HEADS_UP_VIEW_BINDER_CLASSES.flatMap { name ->
            runCatching { Class.forName(name, false, classLoader) }.getOrNull()
                ?.declaredMethods.orEmpty().filter { method ->
                    method.name == "bindHeadsUpView" &&
                        method.returnType == Void.TYPE &&
                        method.parameterTypes.firstOrNull() == entryClass
                }
        }.distinctBy(Method::toGenericString)
        binderMethods.forEach { method ->
            hook(method).setPriority(PRIORITY_HIGHEST).intercept { chain ->
                if (shouldSuppressHeadsUp(entryAccess, chain.args[0])) null else chain.proceed()
            }
            log(Log.INFO, TAG, "Hooked ${method.declaringClass.simpleName}.${method.name}/${method.parameterCount}")
        }
        return binderMethods
    }

    private fun shouldSuppressHeadsUp(access: EntryAccess, entry: Any?): Boolean {
        if (!enabled || !headsUpEnabled || entry == null || !isInteractiveAndUnlocked()) return false
        return runCatching {
            val sbn = access.getSbn(entry)
            val duration = rules[sbn.packageName] ?: return false
            val suppress = headsUpTracker.shouldMute(
                key = "${sbn.uid / PER_USER_RANGE}:${sbn.packageName}",
                durationMs = duration,
                nowMs = SystemClock.elapsedRealtime(),
            )
            if (diagnosticLogsRemaining.getAndDecrement() > 0) {
                log(
                    Log.INFO,
                    TAG,
                    "Heads-up decision pkg=${sbn.packageName}, durationMs=$duration, suppress=$suppress",
                )
            }
            suppress
        }.onFailure {
            log(Log.WARN, TAG, "SystemUI notification inspection failed; allowing heads-up", it)
        }.getOrDefault(false)
    }

    private fun isInteractiveAndUnlocked(): Boolean {
        val context = systemUiContext ?: currentApplication()?.applicationContext?.also {
            systemUiContext = it
        } ?: return false
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return false
        return power.isInteractive && !keyguard.isDeviceLocked
    }

    private fun currentApplication(): Application? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        activityThread.getDeclaredMethod("currentApplication").invoke(null) as? Application
    }.getOrNull()

    private fun suppressDecision(decisionInterface: Class<*>, delegate: Any): Any =
        Proxy.newProxyInstance(
            decisionInterface.classLoader,
            arrayOf(decisionInterface),
        ) { _, method, args ->
            when (method.name) {
                "getShouldInterrupt" -> false
                "getLogReason" -> "QuietNotify window"
                else -> method.invoke(delegate, *(args ?: emptyArray()))
            }
        }

    private fun shouldMuteByRule(record: Any?): Boolean {
        if (!enabled || !soundEnabled || record == null) return false
        return runCatching {
            val access = recordAccess ?: return false
            val sbn = access.getSbn(record)
            val duration = rules[sbn.packageName] ?: return false
            if (!access.canAlert(record)) return false

            soundTracker.shouldMute(
                key = "${sbn.uid / PER_USER_RANGE}:${sbn.packageName}",
                durationMs = duration,
                nowMs = SystemClock.elapsedRealtime(),
            )
        }.onFailure {
            log(Log.WARN, TAG, "Notification inspection failed; allowing alert", it)
        }.getOrDefault(false)
    }

    private fun reloadRules(prefs: SharedPreferences) {
        enabled = prefs.getBoolean(Config.ENABLED, true)
        headsUpEnabled = prefs.getBoolean(Config.HEADS_UP_ENABLED, true)
        soundEnabled = prefs.getBoolean(Config.SOUND_ENABLED, true)
        rules = Config.readRules(prefs)
        if (enabled && soundEnabled) soundTracker.retainPackages(rules.keys) else soundTracker.clear()
        if (enabled && headsUpEnabled) headsUpTracker.retainPackages(rules.keys) else headsUpTracker.clear()
        log(
            Log.INFO,
            TAG,
            "Configuration updated: enabled=$enabled, headsUp=$headsUpEnabled, " +
                "sound=$soundEnabled, rules=${rules.size}",
        )
    }

    private fun logDiagnostic(record: Any?, method: Method, mute: Boolean, systemMuted: Boolean = false) {
        if (record == null || diagnosticLogsRemaining.get() <= 0) return
        runCatching {
            val sbn = recordAccess?.getSbn(record) ?: return
            val duration = rules[sbn.packageName] ?: return
            if (diagnosticLogsRemaining.getAndDecrement() <= 0) return
            log(
                Log.INFO,
                TAG,
                "Decision method=${method.declaringClass.simpleName}.${method.name}, " +
                    "pkg=${sbn.packageName}, durationMs=$duration, systemMuted=$systemMuted, mute=$mute",
            )
        }
    }

    private companion object {
        const val TAG = "QuietNotify"
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val NOTIFICATION_ENTRY_CLASS =
            "com.android.systemui.statusbar.notification.collection.NotificationEntry"
        const val LEGACY_INTERRUPT_PROVIDER_CLASS =
            "com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl"
        const val HEADS_UP_COORDINATOR_CLASS =
            "com.android.systemui.statusbar.notification.collection.coordinator.HeadsUpCoordinator"
        const val PER_USER_RANGE = 100_000
        val HEADS_UP_PROVIDER_CLASSES = listOf(
            "com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderWrapper",
            "com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProviderImpl",
        )
        val HEADS_UP_VIEW_BINDER_CLASSES = listOf(
            "com.android.systemui.statusbar.notification.interruption.HeadsUpViewBinder",
            "com.android.systemui.statusbar.notification.row.HeadsUpViewBinder",
        )
    }

    private class EntryAccess(entryClass: Class<*>) {
        private val getSbn = entryClass.findZeroArgMethod("getSbn")
        private val sbnField = entryClass.findFieldInHierarchy("mSbn")

        init {
            check(getSbn != null || sbnField != null) { "No NotificationEntry SBN accessor" }
        }

        fun getSbn(entry: Any): StatusBarNotification = when {
            getSbn != null -> getSbn.invoke(entry) as StatusBarNotification
            else -> sbnField!!.get(entry) as StatusBarNotification
        }
    }

    private class PostedEntryAccess(postedClass: Class<*>, entryClass: Class<*>) {
        private val getEntry = postedClass.declaredMethods.firstOrNull { method ->
            method.parameterCount == 0 && method.returnType == entryClass &&
                (method.name == "getEntry" || method.name == "component1")
        }?.apply { isAccessible = true }
        private val entryField = postedClass.declaredFields.firstOrNull { field ->
            field.type == entryClass
        }?.apply { isAccessible = true }

        init {
            check(getEntry != null || entryField != null) { "No PostedEntry notification accessor" }
        }

        fun getEntry(posted: Any): Any = getEntry?.invoke(posted) ?: entryField!!.get(posted)
    }

    private class RecordAccess(recordClass: Class<*>) {
        private val getSbn = recordClass.findMethod("getSbn")
        private val sbnField = recordClass.findField("sbn") ?: recordClass.findField("mSbn")
        private val getSound = recordClass.findMethod("getSound")
        private val getVibration = recordClass.findMethod("getVibration")
        private val isIntercepted = recordClass.findMethod("isIntercepted")
        private val shouldPostSilently = recordClass.findMethod("shouldPostSilently")
        private val soundField = recordClass.findField("mSound")
        private val vibrationField = recordClass.findField("mVibration")
        private val postSilentlyField = recordClass.findField("mPostSilently")

        init {
            check(getSbn != null || sbnField != null) { "No NotificationRecord SBN accessor" }
            check(getSound != null || soundField != null) { "No NotificationRecord sound accessor" }
            check(getVibration != null || vibrationField != null) {
                "No NotificationRecord vibration accessor"
            }
        }

        fun getSbn(record: Any): StatusBarNotification = when {
            getSbn != null -> getSbn.invoke(record) as StatusBarNotification
            else -> sbnField!!.get(record) as StatusBarNotification
        }

        fun canAlert(record: Any): Boolean {
            if (isIntercepted?.invoke(record) == true || shouldPostSilently?.invoke(record) == true) {
                return false
            }
            val sound = getSound?.invoke(record) ?: soundField?.get(record)
            val vibration = getVibration?.invoke(record) ?: vibrationField?.get(record)
            return sound != null || vibration != null
        }

        fun muteDuring(record: Any, proceed: () -> Any?): Any? {
            val oldSound = soundField?.get(record)
            val oldVibration = vibrationField?.get(record)
            val oldPostSilently = postSilentlyField?.getBoolean(record)
            check(soundField != null || vibrationField != null || postSilentlyField != null) {
                "No mutable notification alert fields"
            }
            try {
                soundField?.set(record, null)
                vibrationField?.set(record, null)
                postSilentlyField?.setBoolean(record, true)
                return proceed()
            } finally {
                soundField?.set(record, oldSound)
                vibrationField?.set(record, oldVibration)
                if (oldPostSilently != null) postSilentlyField?.setBoolean(record, oldPostSilently)
            }
        }

        private companion object {
            fun Class<*>.findMethod(name: String): Method? {
                var type: Class<*>? = this
                while (type != null) {
                    type.declaredMethods.firstOrNull {
                        it.name == name && it.parameterCount == 0
                    }?.let { return it.apply { isAccessible = true } }
                    type = type.superclass
                }
                return null
            }

            fun Class<*>.findField(name: String): Field? {
                var type: Class<*>? = this
                while (type != null) {
                    val current = type
                    runCatching { current.getDeclaredField(name) }.getOrNull()?.let {
                        return it.apply { isAccessible = true }
                    }
                    type = current.superclass
                }
                return null
            }
        }
    }

}

private fun Class<*>.findZeroArgMethod(name: String): Method? {
    var type: Class<*>? = this
    while (type != null) {
        type.declaredMethods.firstOrNull {
            it.name == name && it.parameterCount == 0
        }?.let { return it.apply { isAccessible = true } }
        type = type.superclass
    }
    return null
}

private fun Class<*>.findFieldInHierarchy(name: String): Field? {
    var type: Class<*>? = this
    while (type != null) {
        val current = type
        runCatching { current.getDeclaredField(name) }.getOrNull()?.let {
            return it.apply { isAccessible = true }
        }
        type = current.superclass
    }
    return null
}
