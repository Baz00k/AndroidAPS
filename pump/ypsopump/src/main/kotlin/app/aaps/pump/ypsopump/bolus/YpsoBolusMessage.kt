package app.aaps.pump.ypsopump.bolus

/**
 * Operator-facing explanations the bolus paths can produce.
 *
 * The driver names the situation; [app.aaps.pump.ypsopump.YpsoPumpPlugin] turns it into text from
 * `strings.xml` so the message the operator reads is translated. Driver layers must not build
 * English sentences: they travel into translated wrappers and would stay untranslated.
 */
enum class YpsoBolusMessage {
    ANOTHER_BOLUS_IN_PROGRESS,
    BOLUS_CANCELLED_BEFORE_START,
    PUMP_BUSY,
    PUMP_UNREADABLE,
    PUMP_STOPPED_OR_EMPTY,
    PUMP_NOT_SET_UP,
    PUMP_NOT_CONNECTED,
    PUMP_ALREADY_BOLUSING,
    PUMP_ALREADY_EXTENDED_BOLUSING,
    PUMP_RESTARTED,
    SYNC_IN_PROGRESS,
    SAVING_PREVIOUS_DOSE,
    CARBS_NOT_STORED,
    CONNECTION_DROPPED_NO_INSULIN,
    CONNECTION_DROPPED_WHILE_SENDING,
    PUMP_DID_NOT_RESPOND,
    MAY_HAVE_BEEN_GIVEN,
    COULD_NOT_COMPLETE,
    NOT_MATCHED_TO_PUMP,
    EXTENDED_NOT_MATCHED_TO_PUMP,
    EXTENDED_NOT_STARTED_BY_AAPS,
    STOPPED_AMOUNT_UNKNOWN,
    STOPPED_AMOUNT_NOT_SAVED,
    NOT_CONFIRMED_FINISHED,
    EXTENDED_NOT_SAVED,
    EXTENDED_STOP_UNCONFIRMED,
    EXTENDED_CANCEL_UNCONFIRMED,
    EXTENDED_CANCEL_NOT_SAVED,
}
