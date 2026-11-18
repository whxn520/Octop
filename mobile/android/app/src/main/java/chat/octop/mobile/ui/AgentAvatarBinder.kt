package chat.octop.mobile.ui

import android.graphics.BitmapFactory
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import chat.octop.mobile.data.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Letter fallback + optional authenticated icon_url for agent avatars. */
object AgentAvatarBinder {
    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

    private const val TAG_JOB = -0x51a11

    fun bind(
        scope: CoroutineScope,
        store: SessionStore,
        letterView: TextView,
        imageView: ImageView,
        name: String,
        iconUrl: String?,
    ) {
        cancel(imageView)
        val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "A"
        letterView.text = initial
        letterView.visibility = View.VISIBLE
        imageView.setImageDrawable(null)
        imageView.visibility = View.GONE

        val raw = iconUrl?.trim().orEmpty()
        if (raw.isEmpty()) return
        val url =
            when {
                raw.startsWith("http://") || raw.startsWith("https://") -> raw
                raw.startsWith("/") -> store.baseUrl.trimEnd('/') + raw
                else -> store.baseUrl.trimEnd('/') + "/" + raw
            }
        val token = store.accessToken
        val job =
            scope.launch {
                val bytes =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val builder = Request.Builder().url(url).get()
                            if (!token.isNullOrBlank()) {
                                builder.header("Authorization", "Bearer $token")
                            }
                            client.newCall(builder.build()).execute().use { resp ->
                                if (!resp.isSuccessful) null else resp.body?.bytes()
                            }
                        }.getOrNull()
                    } ?: return@launch
                val bitmap =
                    withContext(Dispatchers.Default) {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } ?: return@launch
                imageView.outlineProvider = ViewOutlineProvider.BACKGROUND
                imageView.clipToOutline = true
                imageView.setImageBitmap(bitmap)
                imageView.visibility = View.VISIBLE
                letterView.visibility = View.GONE
            }
        imageView.setTag(TAG_JOB, job)
    }

    fun cancel(imageView: ImageView) {
        (imageView.getTag(TAG_JOB) as? Job)?.cancel()
        imageView.setTag(TAG_JOB, null)
    }
}
