package com.eldering.safety.alert

/**
 * What the accident countdown screen (`ui.alert.AccidentAlertScreen`, hosted by `AccidentAlertActivity`) asks the
 * alert pipeline to do. Implemented by the AlertController side; the UI never sends anything itself.
 *
 * Contract for implementations:
 * - Calls arrive on the main thread and must return quickly (start work, do not block).
 * - Every function must be **idempotent**: the UI calls [sendNow] when its own on-screen countdown reaches zero, as a
 *   backstop to the controller's timer, so a second call for an alert already sent must be a no-op. [imOk] after the
 *   alert was cancelled or resolved is a no-op as well.
 * - Nothing here may ever place a call: [callEmergency] opens the dialer (`Intent.ACTION_DIAL`) pre-filled with the
 *   emergency number and leaves the decision to the user.
 */
interface AlertActions {
    /**
     * "I'm OK": during the countdown, cancel the alert (nothing is sent). After it was sent, resolve the incident
     * as `user_ok` so family is told the person is safe.
     */
    fun imOk()

    /** "Send help now": skip the rest of the countdown and alert family and emergency contacts immediately. */
    fun sendNow()

    /** "Call 112": open the dialer with the emergency number (ACTION_DIAL only, never ACTION_CALL). */
    fun callEmergency()
}
