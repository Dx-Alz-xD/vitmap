package com.vitmap.app.nav

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp

val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

private val boundsSpring = spring<Rect>(dampingRatio = 0.86f, stiffness = 320f)

/**
 * Container transform between a tool's home card and the tool screen. Both sides use the same key,
 * so the card grows into the screen and shrinks back on return.
 */
@Composable
fun Modifier.sharedToolBounds(key: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val anim = LocalNavAnimatedScope.current ?: return this
    return with(shared) {
        this@sharedToolBounds.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = anim,
            enter = fadeIn(),
            exit = fadeOut(),
            boundsTransform = { _, _ -> boundsSpring },
            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(28.dp)),
        )
    }
}

/** Shared element (e.g. the tool icon) that flies between screens. */
@Composable
fun Modifier.sharedIcon(key: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val anim = LocalNavAnimatedScope.current ?: return this
    return with(shared) {
        this@sharedIcon.sharedElement(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = anim,
            boundsTransform = { _, _ -> boundsSpring },
        )
    }
}
