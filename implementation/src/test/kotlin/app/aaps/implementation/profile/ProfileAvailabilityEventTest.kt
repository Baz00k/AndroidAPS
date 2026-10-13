package app.aaps.implementation.profile

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.rx.events.EventEffectiveProfileSwitchChanged
import app.aaps.core.interfaces.rx.events.EventProfileAvailabilityChanged
import app.aaps.shared.tests.TestBaseWithProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class ProfileAvailabilityEventTest : TestBaseWithProfile() {
    @Test fun `removing effective profile emits presentation refresh only after cached numeric profile is invalidated`() {
        val persistence: PersistenceLayer = mock()
        val function = ProfileFunctionImpl(aapsLogger, preferences, rxBus, rh, activePlugin, persistence, dateUtil, config, hardLimits, aapsSchedulers, fabricPrivacy, mock())
        val now = dateUtil.now()
        val cached = validProfile
        function.cache[now - now % 1000] = cached
        assertSame(cached, function.getProfile())
        var refreshes = 0
        val subscription = rxBus.toObservable(EventProfileAvailabilityChanged::class.java).subscribe {
            assertNull(function.getProfile(), "The refresh must not see the old cached profile")
            refreshes++
        }
        try {
            // No autosens-completion event or unrelated refresh is sent.
            rxBus.send(EventEffectiveProfileSwitchChanged(now - 60_000))
            assertEquals(1, refreshes)
        } finally {
            subscription.dispose()
        }
    }
}
