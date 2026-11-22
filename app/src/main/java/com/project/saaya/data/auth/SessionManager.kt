package com.project.saaya.data.auth

import com.project.saaya.data.local.AuthSessionStore
import com.project.saaya.data.model.AuthSession

/** One session shared by authentication and the existing Retrofit client. */
class SessionManager(private val store: AuthSessionStore) {
    @Volatile var current: AuthSession? = null
        private set
    var onInvalidated: () -> Unit = {}

    @Synchronized fun saved(): AuthSession? = store.read()

    @Synchronized fun activate(session: AuthSession) {
        store.write(session)
        current = session
    }

    @Synchronized fun clear() {
        current = null
        store.clear()
    }

    /** A late 401 from an old request must not sign out a newer session. */
    @Synchronized fun invalidate(token: String) {
        if (current?.token != token) return
        clear()
        onInvalidated()
    }
}
