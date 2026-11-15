package com.aboezzat.vpn.vpn

/**
 * Bounded bookkeeping for observed TCP flow lifecycles.
 *
 * This is deliberately NOT a TCP implementation: it does not generate ACKs,
 * retransmit segments, negotiate MSS/window scaling, reorder data, or create
 * upstream sockets. It only tracks packet observations for diagnostics and
 * future integration with a real TCP/IP stack.
 */
class TcpFlowLifecycleTracker(
    private val maxFlows: Int = DEFAULT_MAX_FLOWS,
    private val closedRetentionMillis: Long = DEFAULT_CLOSED_RETENTION_MILLIS
) {
    enum class Direction { OUTBOUND, INBOUND }

    enum class State {
        SYN_SENT,
        SYN_ACK_RECEIVED,
        ESTABLISHED,
        LOCAL_HALF_CLOSED,
        REMOTE_HALF_CLOSED,
        CLOSING,
        CLOSED,
        REJECTED
    }

    data class FlowKey(
        val localAddress: String,
        val localPort: Int,
        val remoteAddress: String,
        val remotePort: Int
    )

    data class Snapshot(
        val key: FlowKey,
        val state: State,
        val createdAtMillis: Long,
        val lastSeenAtMillis: Long,
        val localFinSeen: Boolean,
        val remoteFinSeen: Boolean,
        val lastSequenceNumber: Long,
        val lastAcknowledgementNumber: Long,
        val packetsSeen: Long,
        val payloadBytesSeen: Long
    )

    sealed interface ObserveResult {
        data class Updated(val snapshot: Snapshot) : ObserveResult
        data object CapacityReached : ObserveResult
        data object InvalidInitialPacket : ObserveResult
    }

    private data class MutableFlow(
        val key: FlowKey,
        val createdAtMillis: Long,
        var lastSeenAtMillis: Long,
        var state: State,
        var localFinSeen: Boolean = false,
        var remoteFinSeen: Boolean = false,
        var lastSequenceNumber: Long = 0,
        var lastAcknowledgementNumber: Long = 0,
        var packetsSeen: Long = 0,
        var payloadBytesSeen: Long = 0
    ) {
        fun snapshot() = Snapshot(
            key, state, createdAtMillis, lastSeenAtMillis,
            localFinSeen, remoteFinSeen, lastSequenceNumber,
            lastAcknowledgementNumber, packetsSeen, payloadBytesSeen
        )
    }

    private val flows = LinkedHashMap<FlowKey, MutableFlow>()

    init {
        require(maxFlows > 0) { "maxFlows must be positive." }
        require(closedRetentionMillis >= 0) { "closedRetentionMillis cannot be negative." }
    }

    @Synchronized
    fun observe(packet: Ipv4TcpPacketParser.Packet, direction: Direction, nowMillis: Long): ObserveResult {
        require(nowMillis >= 0) { "nowMillis cannot be negative." }
        prune(nowMillis)

        val key = when (direction) {
            Direction.OUTBOUND -> FlowKey(
                packet.tuple.local.address.hostAddress!!,
                packet.tuple.local.port,
                packet.tuple.remote.address.hostAddress!!,
                packet.tuple.remote.port
            )
            Direction.INBOUND -> FlowKey(
                packet.tuple.remote.address.hostAddress!!,
                packet.tuple.remote.port,
                packet.tuple.local.address.hostAddress!!,
                packet.tuple.local.port
            )
        }

        var flow = flows[key]
        if (flow == null) {
            if (direction != Direction.OUTBOUND || !packet.isSyn || packet.isAck || packet.isRst) {
                return ObserveResult.InvalidInitialPacket
            }
            if (flows.size >= maxFlows) return ObserveResult.CapacityReached
            flow = MutableFlow(
                key = key,
                createdAtMillis = nowMillis,
                lastSeenAtMillis = nowMillis,
                state = State.SYN_SENT
            )
            flows[key] = flow
        }

        flow.lastSeenAtMillis = nowMillis
        flow.lastSequenceNumber = packet.sequenceNumber
        flow.lastAcknowledgementNumber = packet.acknowledgementNumber
        flow.packetsSeen++
        flow.payloadBytesSeen += packet.payload.size

        if (packet.isRst) {
            flow.state = State.CLOSED
        } else {
            if (packet.isFin) {
                if (direction == Direction.OUTBOUND) flow.localFinSeen = true
                else flow.remoteFinSeen = true
            }

            if (flow.localFinSeen && flow.remoteFinSeen) {
                flow.state = State.CLOSING
            } else if (flow.localFinSeen) {
                flow.state = State.LOCAL_HALF_CLOSED
            } else if (flow.remoteFinSeen) {
                flow.state = State.REMOTE_HALF_CLOSED
            } else {
                flow.state = when {
                    flow.state == State.SYN_SENT && direction == Direction.INBOUND &&
                        packet.isSyn && packet.isAck -> State.SYN_ACK_RECEIVED
                    flow.state == State.SYN_ACK_RECEIVED && direction == Direction.OUTBOUND &&
                        packet.isAck && !packet.isSyn -> State.ESTABLISHED
                    flow.state == State.SYN_SENT && direction == Direction.OUTBOUND &&
                        packet.isSyn -> State.SYN_SENT
                    flow.state == State.ESTABLISHED -> State.ESTABLISHED
                    else -> flow.state
                }
            }
        }

        return ObserveResult.Updated(flow.snapshot())
    }

    @Synchronized
    fun snapshot(): List<Snapshot> = flows.values.map { it.snapshot() }

    @Synchronized
    fun remove(key: FlowKey): Boolean = flows.remove(key) != null

    @Synchronized
    fun prune(nowMillis: Long): Int {
        require(nowMillis >= 0) { "nowMillis cannot be negative." }
        val before = flows.size
        val iterator = flows.entries.iterator()
        while (iterator.hasNext()) {
            val flow = iterator.next().value
            if (flow.state == State.CLOSED &&
                nowMillis - flow.lastSeenAtMillis >= closedRetentionMillis
            ) {
                iterator.remove()
            }
        }
        return before - flows.size
    }

    companion object {
        const val DEFAULT_MAX_FLOWS = 512
        const val DEFAULT_CLOSED_RETENTION_MILLIS = 30_000L
    }
}
