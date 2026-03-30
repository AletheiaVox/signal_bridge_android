package com.signalbridge.app.relay

import com.signalbridge.app.data.RelayState
import com.signalbridge.app.data.RelayStateHolder
import com.signalbridge.app.util.SBLog

/**
 * Enforces valid state transitions for the relay.
 *
 * DISCONNECTED → CONNECTING → IDLE → ACTIVE → COOLDOWN
 *      ↑              ↓        ↓       ↓         ↓
 *      └──────── ERROR ←───────┴───────┴─────────┘
 *
 * Key rules from the safety brief:
 *  - Reconnect always lands in IDLE, never auto-resumes to ACTIVE
 *  - COOLDOWN can only transition to IDLE (cooldown must complete)
 *  - ERROR can transition to CONNECTING (retry) or DISCONNECTED (give up)
 */
object ConnectionStateMachine {

    private val validTransitions: Map<RelayState, Set<RelayState>> = mapOf(
        RelayState.DISCONNECTED to setOf(RelayState.CONNECTING),
        RelayState.CONNECTING to setOf(RelayState.IDLE, RelayState.ERROR, RelayState.DISCONNECTED),
        RelayState.IDLE to setOf(RelayState.ACTIVE, RelayState.ERROR, RelayState.DISCONNECTED),
        RelayState.ACTIVE to setOf(RelayState.IDLE, RelayState.COOLDOWN, RelayState.ERROR, RelayState.DISCONNECTED),
        RelayState.COOLDOWN to setOf(RelayState.IDLE, RelayState.ERROR, RelayState.DISCONNECTED),
        RelayState.ERROR to setOf(RelayState.CONNECTING, RelayState.DISCONNECTED),
    )

    /**
     * Attempt a state transition. Returns true if the transition was valid and applied.
     */
    fun transition(to: RelayState): Boolean {
        val from = RelayStateHolder.state.value
        val allowed = validTransitions[from] ?: emptySet()

        if (to !in allowed) {
            SBLog.safety("BLOCKED transition $from → $to (not allowed)")
            return false
        }

        SBLog.d("StateMachine", "Transition: $from → $to")
        RelayStateHolder.updateState(to)
        return true
    }

    /**
     * Force a transition regardless of validity. Only for emergency stop.
     */
    fun forceTransition(to: RelayState) {
        val from = RelayStateHolder.state.value
        SBLog.safety("FORCED transition $from → $to")
        RelayStateHolder.updateState(to)
    }

    val currentState: RelayState
        get() = RelayStateHolder.state.value
}
