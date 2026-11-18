package chat.octop.mobile

import android.app.Application
import chat.octop.mobile.agenui.AgenUiBridge
import chat.octop.mobile.data.SessionStore

class OctopApp : Application() {
    lateinit var sessionStore: SessionStore
        private set

    override fun onCreate() {
        super.onCreate()
        sessionStore = SessionStore(this)
        AgenUiBridge.initialize(this)
    }
}
