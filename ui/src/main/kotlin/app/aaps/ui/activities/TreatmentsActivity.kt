package app.aaps.ui.activities

import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.data.model.BS
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.ui.activities.history.HistoryItem
import app.aaps.ui.activities.history.HistoryKind
import app.aaps.ui.activities.history.HistoryScreen
import app.aaps.ui.activities.history.HistoryUiState
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject

/**
 * Redesigned History timeline. UI is Compose ([HistoryScreen]); a unified, day-grouped, read-only list
 * of boluses / carbs / therapy events over the last 14 days, merged from the persistence layer off the
 * main thread. (Edit/delete of individual treatments is a later pass — see the redesign notes.)
 */
class TreatmentsActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var fabricPrivacy: FabricPrivacy

    private val disposable = CompositeDisposable()
    private val historyState = mutableStateOf(HistoryUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(ComposeView(this).apply {
            setContent {
                AapsTheme {
                    HistoryScreen(
                        state = historyState.value,
                        onBack = { finish() },
                        onToggle = ::toggle,
                        onStartSelecting = ::startSelecting,
                        onCancelSelecting = { historyState.value = historyState.value.copy(selecting = false, selected = emptySet()) },
                        onDeleteSelected = ::removeSelected
                    )
                }
            }
        })
        disposable += Single.fromCallable { buildHistory() }
            .subscribeOn(aapsSchedulers.io)
            .observeOn(aapsSchedulers.main)
            .subscribe({ historyState.value = it }, fabricPrivacy::logException)
    }

    private fun toggle(item: HistoryItem) {
        val sel = historyState.value.selected.toMutableSet()
        if (!sel.add(item.key)) sel.remove(item.key)
        historyState.value = historyState.value.copy(selected = sel)
    }

    private fun startSelecting(item: HistoryItem) {
        historyState.value = historyState.value.copy(selecting = true, selected = historyState.value.selected + item.key)
    }

    /**
     * Remove mis-entered treatments.
     *
     * Deliberately a plain tap-to-confirm rather than the press-and-hold used for delivery: nothing is
     * delivered here. The confirmation does spell out the consequence, because it is not obvious —
     * removing a bolus makes the loop see LESS insulin on board and dose more, and removing carbs makes
     * it see less COB. That is the point when the entry was wrong, but it is worth stating.
     *
     * Records are invalidated, not deleted, so the row keeps its history and syncs the removal onward —
     * the same thing upstream's per-type lists did.
     */
    private fun removeSelected() {
        val keys = historyState.value.selected
        val items = historyState.value.items.filter { it.key in keys }
        if (items.isEmpty()) return

        // Plain text, not HTML: the only thing the HTML was buying here was line breaks, and
        // Html.fromHtml collapses runs of whitespace (and ate the breaks), so the summary and the
        // warning ran together into one paragraph.
        val summary = items.sortedByDescending { it.timestamp }.joinToString("\n") {
            it.time + "   " + it.title + (if (it.value.isNotBlank()) "   " + it.value else "")
        }
        val affectsDosing = items.any { it.kind != HistoryKind.EVENT }
        val note = if (affectsDosing)
            "\n\nThis changes IOB/COB, so the loop will recalculate from the corrected history."
        else ""

        OKDialog.showConfirmation(
            this,
            rh.gs(app.aaps.core.ui.R.string.removerecord),
            summary + note,
            {
                items.forEach { item ->
                    val ts = listOf(ValueWithUnit.Timestamp(item.timestamp))
                    disposable += when (item.kind) {
                        HistoryKind.BOLUS,
                        HistoryKind.SMB   -> persistenceLayer.invalidateBolus(item.id, Action.BOLUS_REMOVED, Sources.Treatments, null, ts)

                        HistoryKind.CARBS -> persistenceLayer.invalidateCarbs(item.id, Action.CARBS_REMOVED, Sources.Treatments, null, ts)

                        HistoryKind.EVENT -> persistenceLayer.invalidateTherapyEvent(item.id, Action.CAREPORTAL_REMOVED, Sources.Treatments, null, ts)
                    }.subscribe()
                }
                historyState.value = historyState.value.copy(selecting = false, selected = emptySet())
                reload()
            }
        )
    }

    private fun reload() {
        disposable += Single.fromCallable { buildHistory() }
            .subscribeOn(aapsSchedulers.io)
            .observeOn(aapsSchedulers.main)
            .subscribe({ historyState.value = it }, fabricPrivacy::logException)
    }

    private fun dayLabel(ts: Long, now: Long): String = when (dateUtil.dateString(ts)) {
        dateUtil.dateString(now)                    -> "Today"
        dateUtil.dateString(now - 86_400_000L)      -> "Yesterday"
        else                                        -> dateUtil.dateString(ts)
    }

    private fun eventTitle(type: Any): String =
        type.toString().replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }

    private fun buildHistory(): HistoryUiState {
        val now = dateUtil.now()
        val from = now - 14L * 86_400_000L
        val items = mutableListOf<HistoryItem>()

        persistenceLayer.getBolusesFromTimeToTime(from, now, false).forEach { bs ->
            if (!bs.isValid || bs.type == BS.Type.PRIMING) return@forEach
            val smb = bs.type == BS.Type.SMB
            items += HistoryItem(
                bs.id, bs.timestamp, dayLabel(bs.timestamp, now), dateUtil.timeString(bs.timestamp),
                if (smb) HistoryKind.SMB else HistoryKind.BOLUS,
                if (smb) "SMB" else "Bolus", bs.notes ?: "",
                rh.gs(app.aaps.core.ui.R.string.format_insulin_units, bs.amount)
            )
        }
        // NOT the expanded query: it splits one meal into its absorption series, which showed a single
        // 90 g entry as a run of identical rows — and those slices carry synthetic ids that cannot be
        // invalidated, so nothing in the list would have been removable.
        persistenceLayer.getCarbsFromTimeNotExpanded(from, false).blockingGet().forEach { ca ->
            if (!ca.isValid || ca.timestamp > now) return@forEach
            items += HistoryItem(
                ca.id, ca.timestamp, dayLabel(ca.timestamp, now), dateUtil.timeString(ca.timestamp),
                HistoryKind.CARBS, "Carbs", ca.notes ?: "", "${ca.amount.toInt()} g"
            )
        }
        persistenceLayer.getTherapyEventDataFromToTime(from, now).blockingGet().forEach { te ->
            if (!te.isValid) return@forEach
            items += HistoryItem(
                te.id, te.timestamp, dayLabel(te.timestamp, now), dateUtil.timeString(te.timestamp),
                HistoryKind.EVENT, eventTitle(te.type), te.note ?: "", ""
            )
        }
        items.sortByDescending { it.timestamp }
        return HistoryUiState(loading = false, items = items)
    }

    override fun onDestroy() {
        super.onDestroy()
        disposable.clear()
    }
}
