package app.cuentapepas.ui.live

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Live camera counting screen for an ACTIVE session created by the new-session screen.
 * Contract (stable for navigation): [sessionId] is an existing ACTIVE session; [onFinished] is called with the same
 * id after the session was completed (or abandoned) and saved, so navigation can open the summary.
 * Placeholder body; replaced by Task B10.
 */
@Composable
fun LiveScreen(sessionId: String, onFinished: (sessionId: String) -> Unit) {
    Text("En construcción: $sessionId")
}
