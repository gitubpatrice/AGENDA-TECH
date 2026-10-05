package com.filestech.agenda_tech.ui.screens.month

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pinch over the month, with a real pager underneath — the conflict worth testing is between the
 * two: a pinch whose fingers move sideways is also a sideways drag, and without the pinch taking the
 * events first, the pager would turn the month while the display changes.
 */
@RunWith(AndroidJUnit4::class)
class PinchStepsTest {

    @get:Rule
    val compose = createComposeRule()

    private val steps = mutableListOf<Boolean>()
    private lateinit var pager: PagerState

    private fun showMonthPages() {
        compose.setContent {
            pager = rememberPagerState(initialPage = 1) { PAGES }
            Box(
                modifier = Modifier
                    .size(320.dp)
                    .testTag(AREA)
                    .pinchSteps { spread -> steps += spread },
            ) {
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
    }

    @Test
    fun spreadingTwoFingersSideways_stepsOnceTowardsMoreDetail_andLeavesTheMonthInPlace() {
        showMonthPages()

        compose.onNodeWithTag(AREA).performTouchInput {
            pinch(
                start0 = center - Offset(30f, 0f),
                end0 = center - Offset(220f, 0f),
                start1 = center + Offset(30f, 0f),
                end1 = center + Offset(220f, 0f),
            )
        }
        compose.waitForIdle()

        assertThat(steps).containsExactly(true)
        assertThat(pager.currentPage).isEqualTo(1)
    }

    @Test
    fun bringingTwoFingersTogether_stepsOnceTowardsLessDetail() {
        showMonthPages()

        compose.onNodeWithTag(AREA).performTouchInput {
            pinch(
                start0 = center - Offset(220f, 0f),
                end0 = center - Offset(30f, 0f),
                start1 = center + Offset(220f, 0f),
                end1 = center + Offset(30f, 0f),
            )
        }
        compose.waitForIdle()

        assertThat(steps).containsExactly(false)
        assertThat(pager.currentPage).isEqualTo(1)
    }

    @Test
    fun oneFingerSwipe_turnsTheMonthAndIsNoPinch() {
        // The control the two tests above need: the pager under the pinch does turn on a plain swipe,
        // so "the month stayed in place" above is the pinch's doing, not a pager that cannot move.
        showMonthPages()

        compose.onNodeWithTag(AREA).performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertThat(steps).isEmpty()
        assertThat(pager.currentPage).isEqualTo(2)
    }

    private companion object {
        const val AREA = "area"
        const val PAGES = 3
    }
}
