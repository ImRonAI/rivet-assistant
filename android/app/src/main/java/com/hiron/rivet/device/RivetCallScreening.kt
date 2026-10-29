package com.hiron.rivet.device

import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * Sees each incoming call (with the call-screening role) and tells the runtime, which logs it. Rivet never blocks
 * or silences a call on its own; the call always rings through.
 */
class RivetCallScreening : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        if (details.callDirection == Call.Details.DIRECTION_INCOMING) {
            onIncoming?.invoke(details.handle?.schemeSpecificPart.orEmpty())
        }
        respondToCall(details, CallResponse.Builder().build())
    }

    companion object {
        @Volatile
        var onIncoming: ((String) -> Unit)? = null
    }
}
