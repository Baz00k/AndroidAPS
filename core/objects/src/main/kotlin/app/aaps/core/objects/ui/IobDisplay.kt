package app.aaps.core.objects.ui

/** Presentation only. Unavailable insulin has no activity sample for status BGI. */
data class IobDisplay(
    val total: String,
    val bolus: String,
    val basal: String,
    val unavailableReason: String = "",
    /** Same profile-checked sample as the displayed IOB; null while unavailable. For status BGI. */
    val activity: Double? = null
) {
    val isAvailable: Boolean get() = unavailableReason.isBlank()
}
