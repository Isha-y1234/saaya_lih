package com.project.saaya.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.Gson
import com.project.saaya.data.model.AuthSession
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM with an Android Keystore key. noBackupFilesDir excludes credentials from backup/transfer. */
class EncryptedAuthSessionStore(context: Context) : AuthSessionStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "saaya_auth_session"))
    private val gson = Gson()

    override fun read(): AuthSession? = synchronized(STORE_LOCK) {
        if (!file.baseFile.exists()) return@synchronized null
        try {
            val buffer = ByteBuffer.wrap(file.readFully())
            val ivLength = buffer.int
            require(ivLength == 12 && buffer.remaining() > ivLength)
            val iv = ByteArray(ivLength).also { buffer.get(it) }
            val encrypted = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            gson.fromJson(String(cipher.doFinal(encrypted), Charsets.UTF_8), AuthSession::class.java)
                .also {
                    require(it.token.isNotBlank() && it.user.id.isNotBlank() && it.expiresAt > 0)
                }
        } catch (_: Exception) {
            // Corrupt/transferred data or an invalidated device key must never restore authentication.
            clear()
            null
        }
    }

    override fun write(session: AuthSession): Unit = synchronized(STORE_LOCK) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(gson.toJson(session).toByteArray(Charsets.UTF_8))
        val bytes = ByteBuffer.allocate(4 + cipher.iv.size + encrypted.size)
            .putInt(cipher.iv.size).put(cipher.iv).put(encrypted).array()
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }

    override fun clear(): Unit = synchronized(STORE_LOCK) { file.delete() }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "saaya_auth_session_key"
        // Authentication, background token work, and message delivery share the same AtomicFile/Keystore key.
        val STORE_LOCK = Any()
    }
}
