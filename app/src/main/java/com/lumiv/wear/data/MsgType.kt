package com.lumiv.wear.data

import kotlinx.serialization.SerialName

/**
 * X-API-CR1: Unified message type enum compatible with Android's MsgType.
 * Covers all message types used by Wear OS in the AIP v3 protocol.
 *
 * LOW-FIX (Cross-repo): Added advanced message types from Android's shared protocol
 * to ensure cross-repo message compatibility. Wear OS can now receive and
 * acknowledge advanced types (task pipeline, mesh, handoff, diagnostics)
 * even if it doesn't fully process them.
 *
 * This enum mirrors Android's com.ufo.lumiv.shared.protocol.MsgType for cross-repo
 * message compatibility. Values use snake_case to match server-side wire format.
 *
 * Each enum entry has @SerialName annotation to ensure kotlinx.serialization
 * outputs the snake_case wire value (not the Kotlin enum name).
 */
enum class MsgType(val value: String) {
    /** Authentication message ( Wear OS -> Gateway ) */
    @SerialName("auth")
    AUTH("auth"),
    /** Command message ( Wear OS -> Gateway ) */
    @SerialName("command")
    COMMAND("command"),
    /** Ping/heartbeat for connection keepalive */
    @SerialName("ping")
    PING("ping"),
    /** Command result ( Wear OS -> Gateway ) */
    @SerialName("command_result")
    COMMAND_RESULT("command_result"),
    /** Event message ( Gateway -> Wear OS ) */
    @SerialName("event")
    EVENT("event"),
    /** Liquid Island event for Wear OS notifications ( Gateway -> Wear OS ) */
    @SerialName("liquid_event")
    LIQUID_EVENT("liquid_event"),
    /** VOICE_QUERY: Wear OS voice query uplink */
    @SerialName("voice_query")
    VOICE_QUERY("voice_query"),
    /** PHASE_REPORT: Execution phase reporting */
    @SerialName("phase_report")
    PHASE_REPORT("phase_report"),
    /** STATE_EVENT: Cross-device state synchronization */
    @SerialName("state_event")
    STATE_EVENT("state_event"),
    /** TASK_SUBMIT: Task submission uplink */
    @SerialName("task_submit")
    TASK_SUBMIT("task_submit"),
    /** TASK_ASSIGN: Task assignment downlink */
    @SerialName("task_assign")
    TASK_ASSIGN("task_assign"),
    /** TASK_RESULT: Task execution result */
    @SerialName("task_result")
    TASK_RESULT("task_result"),
    /** GOAL_EXECUTION: Goal execution request */
    @SerialName("goal_execution")
    GOAL_EXECUTION("goal_execution"),
    /** PARALLEL_SUBTASK: Parallel subtask dispatch */
    @SerialName("parallel_subtask")
    PARALLEL_SUBTASK("parallel_subtask"),
    /** GOAL_RESULT: Goal execution result */
    @SerialName("goal_result")
    GOAL_RESULT("goal_result"),
    /** GOAL_EXECUTION_RESULT: Goal execution result (alias) */
    @SerialName("goal_execution_result")
    GOAL_EXECUTION_RESULT("goal_execution_result"),
    /** TASK_CANCEL: Task cancellation request */
    @SerialName("task_cancel")
    TASK_CANCEL("task_cancel"),
    /** CANCEL_RESULT: Task cancellation result */
    @SerialName("cancel_result")
    CANCEL_RESULT("cancel_result"),
    /** MESH_JOIN: Mesh session participation */
    @SerialName("mesh_join")
    MESH_JOIN("mesh_join"),
    /** MESH_LEAVE: Mesh session exit */
    @SerialName("mesh_leave")
    MESH_LEAVE("mesh_leave"),
    /** MESH_RESULT: Mesh operation result */
    @SerialName("mesh_result")
    MESH_RESULT("mesh_result"),
    /** Heartbeat acknowledgement */
    @SerialName("heartbeat_ack")
    HEARTBEAT_ACK("heartbeat_ack"),

    // ── Cross-repo LOW-FIX: Advanced message types for minimal-compat handling ──

    /** DEVICE_REGISTER: Device registration */
    @SerialName("device_register")
    DEVICE_REGISTER("device_register"),

    /** CAPABILITY_REPORT: Device capability report */
    @SerialName("capability_report")
    CAPABILITY_REPORT("capability_report"),

    /** HEARTBEAT: Heartbeat request */
    @SerialName("heartbeat")
    HEARTBEAT("heartbeat"),

    /** DIAGNOSTICS_PAYLOAD: Diagnostic data from device */
    @SerialName("diagnostics_payload")
    DIAGNOSTICS_PAYLOAD("diagnostics_payload"),

    /** DEVICE_STATE_SNAPSHOT: Full device state snapshot */
    @SerialName("device_state_snapshot")
    DEVICE_STATE_SNAPSHOT("device_state_snapshot"),

    /** TAKEOVER_REQUEST: Handoff takeover request */
    @SerialName("takeover_request")
    TAKEOVER_REQUEST("takeover_request"),

    /** TAKEOVER_RESPONSE: Handoff takeover response */
    @SerialName("takeover_response")
    TAKEOVER_RESPONSE("takeover_response"),

    /** HANDOFF_ENVELOPE_V2: Handoff envelope v2 */
    @SerialName("handoff_envelope_v2")
    HANDOFF_ENVELOPE_V2("handoff_envelope_v2"),

    /** OPERATOR_ACTION_REQUEST: Operator action request */
    @SerialName("operator_action_request")
    OPERATOR_ACTION_REQUEST("operator_action_request"),

    /** RELAY: Relay message forwarding */
    @SerialName("relay")
    RELAY("relay"),

    /** WAKE_EVENT: Wake/signal event */
    @SerialName("wake_event")
    WAKE_EVENT("wake_event"),

    /** BROADCAST: Broadcast message */
    @SerialName("broadcast")
    BROADCAST("broadcast"),

    /** ACK: Acknowledgement */
    @SerialName("ack")
    ACK("ack");

    companion object {
        /** Backing O(1) lookup map for [fromValue]. Built once at class-load time. */
        private val VALUE_MAP: Map<String, MsgType> = entries.associateBy { it.value }

        /**
         * Looks up a [MsgType] by its wire-format [value] string.
         * Returns `null` when [value] does not match any known type.
         */
        fun fromValue(value: String): MsgType? = VALUE_MAP[value]

        /**
         * LOW-FIX (Cross-repo): Set of advanced message types that receive minimal-compat
         * handling on Wear OS (log + optional ack). Wear OS acknowledges these types
         * to maintain protocol compatibility with Android/Gateway even though it may
         * not fully process them.
         */
        val ADVANCED_TYPES: Set<MsgType> = setOf(
            RELAY, WAKE_EVENT, BROADCAST, ACK,
            TAKEOVER_REQUEST, TAKEOVER_RESPONSE,
            HANDOFF_ENVELOPE_V2,
            DIAGNOSTICS_PAYLOAD, DEVICE_STATE_SNAPSHOT,
            OPERATOR_ACTION_REQUEST
        )

        /**
         * LOW-FIX (Cross-repo): Advanced types for which Wear OS should send an
         * ack reply on receipt. This prevents the gateway from retrying messages
         * that the watch has already received.
         */
        val ACK_ON_RECEIPT_TYPES: Set<MsgType> = setOf(
            RELAY, WAKE_EVENT, TAKEOVER_REQUEST
        )

        /**
         * LOW-FIX (Cross-repo): Task pipeline types. Wear OS receives these for
         * status display but does not execute tasks.
         */
        val TASK_PIPELINE_TYPES: Set<MsgType> = setOf(
            TASK_SUBMIT, TASK_ASSIGN, TASK_RESULT,
            GOAL_EXECUTION, GOAL_RESULT,
            PARALLEL_SUBTASK, TASK_CANCEL, CANCEL_RESULT
        )
    }
}
