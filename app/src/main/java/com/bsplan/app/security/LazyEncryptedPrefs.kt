package com.bsplan.app.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureStorageUnavailableException(message: String, cause: Throwable) :
    IllegalStateException(message, cause)

class LazyEncryptedPrefs(
    private val context: Context,
    private val prefsName: String,
    private val tag: String,
    private val legacyPrefsName: String? = null
) {
    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var initError: Throwable? = null

    // Phase 3 (Oct 2026): a failure is retried after this long instead of being kept for the
    // life of the process
    @Volatile
    private var initErrorAtMs = 0L

    fun getOrNull(): SharedPreferences? {
        return try {
            getOrThrow()
        } catch (_: SecureStorageUnavailableException) {
            null
        }
    }

    fun getOrThrow(): SharedPreferences {
        prefs?.let { return it }

        recentError()?.let {
            throw SecureStorageUnavailableException(
                "Encrypted preferences unavailable for $prefsName",
                it
            )
        }

        synchronized(this) {
            prefs?.let { return it }
            recentError()?.let {
                throw SecureStorageUnavailableException(
                    "Encrypted preferences unavailable for $prefsName",
                    it
                )
            }

            return try {
                create()
            } catch (first: Throwable) {
                if (!isUndecryptable(first)) return fail(first)
                // A copy restored from another install (or a lost Keystore key) can't be decrypted
                // here; its contents are unrecoverable, so start the file fresh once
                Log.w(tag, "Encrypted preferences $prefsName can't be decrypted; recreating", first)
                try {
                    context.deleteSharedPreferences(prefsName)
                    create()
                } catch (second: Throwable) {
                    fail(second)
                }
            }
        }
    }

    private fun create(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        val target = EncryptedSharedPreferences.create(
            context,
            prefsName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        migrateLegacyIfNeeded(target, masterKey)
        return target.also {
            prefs = it
            initError = null
        }
    }

    private fun migrateLegacyIfNeeded(target: SharedPreferences, masterKey: MasterKey) {
        val legacyName = legacyPrefsName ?: return
        if (target.all.isNotEmpty()) return
        val legacyFile = java.io.File(context.applicationInfo.dataDir, "shared_prefs/$legacyName.xml")
        if (!legacyFile.exists()) return
        runCatching {
            val legacy = EncryptedSharedPreferences.create(
                context,
                legacyName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            val entries = legacy.all
            if (entries.isNotEmpty()) {
                val editor = target.edit()
                for ((k, v) in entries) {
                    when (v) {
                        is String -> editor.putString(k, v)
                        is Boolean -> editor.putBoolean(k, v)
                        is Int -> editor.putInt(k, v)
                        is Long -> editor.putLong(k, v)
                        is Float -> editor.putFloat(k, v)
                        is Set<*> -> @Suppress("UNCHECKED_CAST") editor.putStringSet(k, v as Set<String>)
                    }
                }
                editor.commit()
            }
            context.deleteSharedPreferences(legacyName)
            Log.i(tag, "Migrated encrypted preferences from $legacyName to $prefsName")
        }.onFailure { e ->
            Log.w(tag, "Could not migrate legacy preferences $legacyName: ${e.message}")
        }
    }

    private fun fail(t: Throwable): Nothing {
        initError = t
        initErrorAtMs = System.currentTimeMillis()
        Log.e(tag, "Failed to initialize encrypted preferences: $prefsName", t)
        throw SecureStorageUnavailableException(
            "Encrypted preferences unavailable for $prefsName",
            t
        )
    }

    /** The last failure, if it is recent enough not to retry yet. */
    private fun recentError(): Throwable? =
        initError?.takeIf { System.currentTimeMillis() - initErrorAtMs < RETRY_AFTER_MS }

    /** A decryption failure (wrong key for the stored keyset), not e.g. a busy Keystore. */
    private fun isUndecryptable(t: Throwable): Boolean =
        generateSequence(t) { it.cause }.any {
            it is javax.crypto.AEADBadTagException || it.javaClass.name.endsWith("InvalidProtocolBufferException")
        }

    private companion object {
        const val RETRY_AFTER_MS = 60_000L
    }
}
