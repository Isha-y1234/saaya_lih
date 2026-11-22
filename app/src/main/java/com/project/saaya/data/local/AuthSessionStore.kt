package com.project.saaya.data.local

import com.project.saaya.data.model.AuthSession

interface AuthSessionStore {
    fun read(): AuthSession?
    fun write(session: AuthSession)
    fun clear()
}
