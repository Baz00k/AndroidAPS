package app.aaps.ui.activities

import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import app.aaps.core.compose.theme.AapsTheme
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
import app.aaps.ui.activities.history.StillRunningException
import app.aaps.ui.activities.history.HistoryReloader
import app.aaps.ui.activities.history.refreshedWith
import app.aaps.ui.activities.history.startedSelecting
import app.aaps.ui.activities.history.toggled
import app.aaps.ui.activities.history.toHistoryItem
import app.aaps.ui.activities.history.confirmHistoryRemoval
import app.aaps.ui.activities.history.invalidateHistoryItems
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject

/**
 * Redesigned History timeline. UI is Compose ([HistoryScreen]); a unified, day-grouped list
 * of boluses / extended boluses / carbs / temporary basals / therapy events over the last 14 days, merged from the
 * persistence layer off the main thread. Removal invalidates persisted history; it does not command the pump, so records
 * of delivery still in progress (running temporary basal or extended bolus) cannot be removed.
 */
class TreatmentsActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var fabricPrivacy: FabricPrivacy

    private val disposable = CompositeDisposable()
    private val reloader by lazy {
        HistoryReloader(
            load = { buildHistory().items },
            io = aapsSchedulers.io,
            main = aapsSchedulers.main,
            now = dateUtil::now,
            // A snapshot taken before a removal finished must not replace the list mid-removal; the removal
            // always reloads once it completes.
            onLoaded = { fresh -> if (!historyState.value.removing) historyState.value = historyState.value.refreshedWith(fresh) },
            onError = fabricPrivacy::logException
        )
    }
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
    }

    override fun onResume() {
        super.onResume()
        reloader.resume()
    }

    override fun onPause() {
        super.onPause()
        reloader.stop()
    }

    private fun toggle(item: HistoryItem) {
        historyState.value = historyState.value.toggled(item)
    }

    private fun startSelecting(item: HistoryItem) {
        historyState.value = historyState.value.startedSelecting(item)
    }

    /** Correct history through persistence only. Never cancel or otherwise command pump delivery. */
    private fun removeSelected() {
        if (historyState.value.removing) return
        val keys = historyState.value.selected
        val items = historyState.value.items.filter { it.key in keys }
        confirmHistoryRemoval(
            items,
            showConfirmation = { message, confirm ->
                OKDialog.showConfirmation(this, rh.gs(app.aaps.core.ui.R.string.removerecord), message, confirm)
            },
            onConfirmed = { selection ->
                reloader.cancel()
                historyState.value = historyState.value.copy(removing = true)
                disposable += invalidateHistoryItems(persistenceLayer, selection)
                    .subscribeOn(aapsSchedulers.io)
                    .observeOn(aapsSchedulers.main)
                    .subscribe({ result ->
                        historyState.value = result.applyTo(historyState.value)
                        reloader.reload()
                        if (result.failures.isNotEmpty()) {
                            result.failures.map { it.second }.filterNot { it is StillRunningException }.forEach(fabricPrivacy::logException)
                            OKDialog.show(this, "Removal failed", result.failureMessage())
                        }
                    }, { error ->
                        historyState.value = historyState.value.copy(removing = false)
                        reloader.reload()
                        fabricPrivacy.logException(error)
                        OKDialog.show(this, "Removal failed", "Could not finish removing the selected records. Reopen History to refresh the list before retrying.")
                    })
            }
        )
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
            bs.toHistoryItem(dayLabel(bs.timestamp, now), dateUtil.timeString(bs.timestamp), rh)?.let(items::add)
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
        persistenceLayer.getExtendedBolusesStartingFromTimeToTime(from, now, false).forEach { eb ->
            eb.toHistoryItem(from, now, dayLabel(eb.timestamp, now), dateUtil.timeString(eb.timestamp))?.let(items::add)
        }
        persistenceLayer.getTemporaryBasalsStartingFromTimeToTime(from, now, false).forEach { basal ->
            basal.toHistoryItem(from, now, dayLabel(basal.timestamp, now), dateUtil.timeString(basal.timestamp))?.let(items::add)
        }
        items.sortByDescending { it.timestamp }
        return HistoryUiState(loading = false, items = items)
    }

    override fun onDestroy() {
        super.onDestroy()
        reloader.stop()
        disposable.clear()
    }
}
