package com.project.saaya.data.local

import android.content.Context
import android.util.AtomicFile
import com.project.saaya.data.repository.TokenRegistrationCache
import java.io.File

/** Only a digest and timestamp, excluded from backup; no registration/session token is stored here. */
class FcmRegistrationStore(context: Context) : TokenRegistrationCache {
    private val file = AtomicFile(File(context.noBackupFilesDir, "saaya_fcm_registration"))

    @Synchronized override fun matches(fingerprint: String, nowMillis: Long): Boolean = try {
        val parts = String(file.readFully(), Charsets.UTF_8).split('\n')
        parts[0] == fingerprint && nowMillis - parts[1].toLong() in 0 until 24 * 60 * 60 * 1000L
    } catch (_: Exception) { false }

    @Synchronized override fun save(fingerprint: String, nowMillis: Long) {
        val output = file.startWrite()
        try {
            output.write("$fingerprint\n$nowMillis".toByteArray())
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }
    @Synchronized override fun clear() { file.delete() }
}
