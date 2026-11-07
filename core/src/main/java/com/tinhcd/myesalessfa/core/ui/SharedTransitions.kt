package com.tinhcd.myesalessfa.core.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.OverlayClip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape

/**
 * The navigation host's shared-transition scope, set once around the NavHost.
 * Null in previews and tests, where nothing is navigating.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/**
 * The destination's own enter/exit animation, set by each destination that
 * takes part in a shared transition. Null elsewhere.
 */
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Keys for the elements that travel between screens; one place so both ends agree. */
object SharedKeys {
    /** The shop's picture: the avatar on a route card, the banner on its own screen. */
    fun store(customerId: String) = "store-$customerId"
}

/**
 * Marks a container that morphs into its counterpart on the next screen -- the
 * same [key] on both -- growing or shrinking between their bounds while the
 * two contents cross-fade, clipped to [shape] as it moves.
 *
 * It follows the navigation's own progress, so a predictive back gesture
 * scrubs it: dragging back from a shop's screen shrinks the banner towards
 * the card it came from, and letting go part-way puts it back.
 *
 * Does nothing outside a shared transition, so a screen drawn in a preview,
 * a test, or a destination that does not take part looks exactly as before.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedContainer(key: String, shape: Shape): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalNavAnimatedScope.current ?: return this
    return with(shared) {
        this@sharedContainer.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = animated,
            clipInOverlayDuringTransition = OverlayClip(shape),
        )
    }
}
