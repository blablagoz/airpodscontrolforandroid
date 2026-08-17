package com.avni.airpodscontrol.service

import com.avni.airpodscontrol.model.AirPodsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object AirPodsRuntime {
    private val _state = MutableStateFlow(AirPodsState())
    val state: StateFlow<AirPodsState> = _state

    fun update(transform: (AirPodsState) -> AirPodsState) {
        _state.value = transform(_state.value)
    }

    fun replace(newState: AirPodsState) {
        val old = _state.value
        _state.value = newState.copy(
            overlayEnabled = old.overlayEnabled,
            aclConnected = old.aclConnected,
            aclTransport = old.aclTransport,
            a2dpConnected = old.a2dpConnected,
            headsetConnected = old.headsetConnected,
            discoveredUuids = old.discoveredUuids
        )
    }
}
