package app.cameraderie.core

/** Only the current DJI control connection can complete setup and authorize the protocol. */
internal class DjiGattSetup<Connection : Any> {
    private enum class Phase { CONNECTING, SUBSCRIBING, SUBSCRIBED, READY }

    var connection: Connection? = null
        private set
    private var phase = Phase.CONNECTING
    val ready get() = phase == Phase.READY

    fun open(connection: Connection) {
        check(this.connection == null)
        this.connection = connection
    }

    fun isCurrent(connection: Connection) = this.connection === connection

    fun beginSubscription(connection: Connection) = advance(connection, Phase.CONNECTING, Phase.SUBSCRIBING)

    fun subscriptionCompleted(connection: Connection) = advance(connection, Phase.SUBSCRIBING, Phase.SUBSCRIBED)

    fun markReady(connection: Connection) = advance(connection, Phase.SUBSCRIBED, Phase.READY)

    fun close(): Connection? {
        val old = connection
        connection = null
        phase = Phase.CONNECTING
        return old
    }

    private fun advance(connection: Connection, from: Phase, to: Phase): Boolean {
        if (!isCurrent(connection) || phase != from) return false
        phase = to
        return true
    }
}
