package com.excp.podroid.hostservices

import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MicrophonePhase { OFF, WAITING, RECORDING, FAILED }

@Singleton
class MicrophoneSession @Inject constructor() {
    @Volatile var token: String? = null
        private set
    private val mutableState = MutableStateFlow(MicrophonePhase.OFF)
    val state = mutableState.asStateFlow()
    fun prepare(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }.also { token = it }
    fun clear() { token = null }
    fun publish(value: MicrophonePhase) { mutableState.value = value }
}
