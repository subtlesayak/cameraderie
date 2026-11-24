package app.cameraderie.core

data class TargetDelivery(val state: State, val detail: String) {
    enum class State { WAITING, SENT, ACKNOWLEDGED, FAILED, UNKNOWN }

    companion object {
        fun failed(detail: String) = TargetDelivery(State.FAILED, detail)
        fun sent(detail: String) = TargetDelivery(State.SENT, detail)
    }
}
