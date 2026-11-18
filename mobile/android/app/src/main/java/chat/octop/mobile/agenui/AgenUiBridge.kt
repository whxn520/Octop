package chat.octop.mobile.agenui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Thin façade over AGenUI. When the AAR is present on the classpath, reflection
 * wires SurfaceManager; otherwise chunks are logged and a placeholder view is used
 * so the chat shell still builds and runs.
 */
object AgenUiBridge {
    private const val TAG = "AgenUiBridge"
    private var initialized = false
    private var sdkAvailable = false

    fun initialize(app: Application) {
        if (initialized) return
        initialized = true
        try {
            val clazz = Class.forName("com.amap.genui.AGenUI")
            val instance = clazz.getMethod("getInstance").invoke(null)
            clazz.getMethod("initialize", Context::class.java).invoke(instance, app)
            sdkAvailable = true
            Log.i(TAG, "AGenUI SDK initialized")
        } catch (t: Throwable) {
            sdkAvailable = false
            Log.i(TAG, "AGenUI SDK not on classpath; using stub renderer")
        }
    }

    fun createSession(activity: Activity, container: ViewGroup): Session {
        return if (sdkAvailable) ReflectiveSession(activity, container) else StubSession(container)
    }

    interface Session {
        fun beginTextStream()
        fun receiveTextChunk(chunk: String)
        fun endTextStream()
        fun destroy()
    }

    private class StubSession(container: ViewGroup) : Session {
        private val placeholder =
            View(container.context).apply {
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                    )
            }

        init {
            container.removeAllViews()
            container.addView(placeholder)
        }

        override fun beginTextStream() {
            Log.d(TAG, "stub beginTextStream")
        }

        override fun receiveTextChunk(chunk: String) {
            Log.d(TAG, "stub chunk len=${chunk.length}")
        }

        override fun endTextStream() {
            Log.d(TAG, "stub endTextStream")
        }

        override fun destroy() {
            (placeholder.parent as? ViewGroup)?.removeView(placeholder)
        }
    }

    private class ReflectiveSession(
        activity: Activity,
        private val container: ViewGroup,
    ) : Session {
        private val manager: Any =
            Class.forName("com.amap.genui.SurfaceManager")
                .getConstructor(Activity::class.java)
                .newInstance(activity)

        init {
            val listenerClass = Class.forName("com.amap.genui.ISurfaceManagerListener")
            val proxy =
                java.lang.reflect.Proxy.newProxyInstance(
                    listenerClass.classLoader,
                    arrayOf(listenerClass),
                ) { _, method, args ->
                    when (method.name) {
                        "onCreateSurface" -> {
                            val surface = args?.getOrNull(0)
                            val getContainer = surface?.javaClass?.getMethod("getContainer")
                            val view = getContainer?.invoke(surface) as? View
                            if (view != null) {
                                activity.runOnUiThread {
                                    container.removeAllViews()
                                    container.addView(view)
                                }
                            }
                        }
                        else -> Unit
                    }
                    null
                }
            manager.javaClass.getMethod("addListener", listenerClass).invoke(manager, proxy)
        }

        override fun beginTextStream() {
            manager.javaClass.getMethod("beginTextStream").invoke(manager)
        }

        override fun receiveTextChunk(chunk: String) {
            manager.javaClass.getMethod("receiveTextChunk", String::class.java).invoke(manager, chunk)
        }

        override fun endTextStream() {
            manager.javaClass.getMethod("endTextStream").invoke(manager)
        }

        override fun destroy() {
            runCatching { manager.javaClass.getMethod("destroy").invoke(manager) }
            container.removeAllViews()
        }
    }
}
