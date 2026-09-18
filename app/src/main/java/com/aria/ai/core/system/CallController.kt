package com.aria.ai.core.system

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Call handling for the call/screen agent: read line state, answer, hang up and
 * dial — using only the platform Telephony/Telecom services. Every capability is
 * permission-gated and returns an explanatory sentence rather than throwing.
 */
@Singleton
class CallController @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val telephony: TelephonyManager? =
        context.getSystemService(TelephonyManager::class.java)

    private val telecom: TelecomManager? =
        context.getSystemService(TelecomManager::class.java)

    private fun has(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Current line state (IDLE / RINGING / OFFHOOK). */
    @Suppress("DEPRECATION")
    fun currentState(): Int = runCatching { telephony?.callState ?: IDLE }.getOrDefault(IDLE)

    /** Emits line-state changes; requires READ_PHONE_STATE at runtime. */
    fun monitor(): Flow<Int> = callbackFlow {
        val manager = telephony
        if (manager == null || !has(Manifest.permission.READ_PHONE_STATE)) {
            trySend(currentState())
            close()
            return@callbackFlow
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = CallStateCallback { state -> trySend(state) }
            val registered = runCatching {
                manager.registerTelephonyCallback(context.mainExecutor, callback)
            }.isSuccess
            if (!registered) {
                trySend(currentState())
                close()
                return@callbackFlow
            }
            awaitClose { runCatching { manager.unregisterTelephonyCallback(callback) } }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : android.telephony.PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    trySend(state)
                }
            }
            @Suppress("DEPRECATION")
            manager.listen(listener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
            awaitClose {
                @Suppress("DEPRECATION")
                runCatching {
                    manager.listen(listener, android.telephony.PhoneStateListener.LISTEN_NONE)
                }
            }
        }
    }.conflate().flowOn(Dispatchers.Main)

/** Hangs up the active call (Android 9+ / ANSWER_PHONE_CALLS). */
    fun endCall(): String {
        if (!has(Manifest.permission.ANSWER_PHONE_CALLS)) {
            return "I need the call-control permission before I can hang up."
        }
        val manager = telecom ?: return "Telecom service is unavailable on this device."
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return "Ending calls requires Android 9 or newer."
        }
        return runCatching {
            manager.endCall()
            "Call ended."
        }.getOrElse { "There is no active call to end." }
    }

    /** Answers the ringing call (Android 9+ / ANSWER_PHONE_CALLS). */
    fun answerCall(): String {
        if (!has(Manifest.permission.ANSWER_PHONE_CALLS)) {
            return "I need the call-control permission before I can answer."
        }
        val manager = telecom ?: return "Telecom service is unavailable on this device."
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return "Answering calls requires Android 9 or newer."
        }
        return runCatching {
            manager.acceptRingingCall()
            "Answering the call."
        }.getOrElse { "Nothing is ringing right now." }
    }

    /** Places a call, e.g. "call 9876543210". */
    fun placeCall(rawNumber: String): String {
        val number = rawNumber.filter { it.isDigit() || it == '+' || it == ' ' }.trim()
        if (number.isBlank()) return "I could not read the number to dial."
        if (!has(Manifest.permission.CALL_PHONE)) {
            return "I need the phone permission to dial $number."
        }
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            "Dialling $number."
        }.getOrElse { "I could not start the call." }
    }

    fun isRinging(): Boolean = currentState() == RINGING

    fun describeState(): String = when (currentState()) {
        RINGING -> "The phone is ringing."
        OFFHOOK -> "A call is in progress."
        else -> "No call is active."
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private class CallStateCallback(
        private val onState: (Int) -> Unit
    ) : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            onState(state)
        }
    }

    companion object {
        const val IDLE = TelephonyManager.CALL_STATE_IDLE
        const val RINGING = TelephonyManager.CALL_STATE_RINGING
        const val OFFHOOK = TelephonyManager.CALL_STATE_OFFHOOK
    }
}