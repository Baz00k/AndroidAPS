package app.aaps.core.interfaces.rx.events

import app.aaps.core.interfaces.notifications.Notification

/** One ordered stream for atomic replacement or removal of a publisher-owned notification. */
class EventSetNotification(val id: Int, val notification: Notification?) : Event() {
    init { require(notification == null || notification.id == id) }
}
