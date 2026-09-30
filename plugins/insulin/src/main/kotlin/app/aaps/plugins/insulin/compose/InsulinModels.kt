package app.aaps.plugins.insulin.compose

/** Presentation state for the redesigned Insulin curve screen (handoff Section 6 — Insulin). */
data class InsulinUiState(
    val activeName: String = "",
    val comment: String = "",
    val diaHours: Double = 0.0,
    val peakMinutes: Int = 0
)
