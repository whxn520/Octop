package chat.octop.mobile.push

import android.util.Log
import chat.octop.mobile.OctopApp
import chat.octop.mobile.data.OctopApi
import kotlin.concurrent.thread

/**
 * Registers an FCM token with Octop when Firebase is on the classpath.
 * Default debug builds ship without Firebase; this becomes a no-op.
 */
object PushRegistrar {
    private const val TAG = "PushRegistrar"
    private const val PREF = "octop_fcm"
    private const val KEY_TOKEN = "token"

    fun registerAsync(app: OctopApp, token: String) {
        app.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, token)
            .apply()
        if (app.sessionStore.accessToken.isNullOrBlank()) return
        thread {
            runCatching {
                OctopApi(app.sessionStore).registerPushDevice(token)
                Log.i(TAG, "push device registered")
            }.onFailure { Log.w(TAG, "push register failed: ${it.message}") }
        }
    }

    fun registerCurrentIfPossible(app: OctopApp) {
        val cached =
            app.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
                .getString(KEY_TOKEN, null)
        if (!cached.isNullOrBlank()) {
            registerAsync(app, cached)
            return
        }
        runCatching {
            val messagingClass = Class.forName("com.google.firebase.messaging.FirebaseMessaging")
            val instance = messagingClass.getMethod("getInstance").invoke(null)
            val task = messagingClass.getMethod("getToken").invoke(instance)
            val addListener =
                task.javaClass.methods.first {
                    it.name == "addOnSuccessListener" && it.parameterTypes.size == 1
                }
            val listenerType = addListener.parameterTypes[0]
            val proxy =
                java.lang.reflect.Proxy.newProxyInstance(
                    listenerType.classLoader,
                    arrayOf(listenerType),
                ) { _, method, args ->
                    if (method.name == "onSuccess" || method.name == "onComplete") {
                        val token = args?.getOrNull(0)?.toString()
                        if (!token.isNullOrBlank()) {
                            registerAsync(app, token)
                        }
                    }
                    null
                }
            addListener.invoke(task, proxy)
        }.onFailure { Log.i(TAG, "Firebase not configured: ${it.message}") }
    }
}
