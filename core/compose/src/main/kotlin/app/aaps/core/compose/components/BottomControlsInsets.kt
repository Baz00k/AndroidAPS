package app.aaps.core.compose.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable

/**
 * What controls at the bottom of the screen must clear: the navigation bar and the home gesture area.
 * On gesture navigation that area is taller than the handle it draws, and a press in it (a hold most
 * of all) can be taken by the system as the home or Assistant gesture. Surfaces still paint under it.
 * Bottom only: a host already keeps clear of a navigation bar at the side.
 */
val WindowInsets.Companion.bottomControls: WindowInsets
    @Composable get() = navigationBars.union(mandatorySystemGestures).only(WindowInsetsSides.Bottom)
