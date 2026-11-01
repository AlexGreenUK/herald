package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.designsystem.AccentPalette
import dev.hermeskotlin.designsystem.hermesTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The sidebar's profile switch: one button, read as the profile it shows, that asks for the profile picker. */
@OptIn(ExperimentalTestApi::class)
class ProfileSwitchRowTest {

    @Test
    fun aTapAsksForTheProfilePickerAndTheButtonIsReadAsTheProfile() = runComposeUiTest {
        var opened = 0
        setContent {
            hermesTheme(AccentPalette.Blue)(ColorScheme.Dark) {
                // As narrow as a 360dp phone's drawer, with a name far too long for it.
                Box(Modifier.width(300.dp)) {
                    ProfileSwitchRow(
                        ProfileSwitch("Research assistant for the garden club newsletter and more"),
                        onClick = { opened++ },
                        modifier = Modifier.testTag("switch"),
                    )
                }
            }
        }
        val node = onNodeWithTag("switch").fetchSemanticsNode()
        assertEquals(
            listOf("Profile: Research assistant for the garden club newsletter and more"),
            node.config.getOrNull(SemanticsProperties.ContentDescription),
        )
        assertEquals(Role.Button, node.config.getOrNull(SemanticsProperties.Role))
        assertEquals("Switch profile", node.config.getOrNull(SemanticsActions.OnClick)?.label)

        onNodeWithTag("switch").performClick()
        waitForIdle()
        assertEquals(1, opened)
    }

    @Test
    fun aLongNameIsCutShortAndTheChevronStaysAtTheEnd() = runComposeUiTest {
        setContent {
            hermesTheme(AccentPalette.Blue)(ColorScheme.Dark) {
                Column {
                    for ((tag, name) in listOf("short" to "Work", "long" to "Research assistant for the garden club newsletter and more")) {
                        Box(Modifier.width(300.dp).testTag(tag)) { ProfileSwitchRow(ProfileSwitch(name), onClick = {}) }
                    }
                }
            }
        }
        val short = onNodeWithTag("short").captureToImage().toPixelMap()
        val long = onNodeWithTag("long").captureToImage().toPixelMap()
        // The chevron's place, 12dp in from the pill's end: drawn the same whatever the name's length.
        val px = density.density
        val xs = (short.width - (12 + 18) * px).toInt() until (short.width - 10 * px).toInt()
        val ys = (short.height / 4) until (short.height * 3 / 4)
        val differing = xs.sumOf { x -> ys.count { y -> short[x, y] != long[x, y] } }
        assertEquals(0, differing, "The long name ran into the chevron's place")
        // And the chevron is there to see in it, not only the pill's fill.
        assertTrue(xs.any { x -> ys.any { y -> short[x, y] != short[xs.first, ys.first] } })
    }
}
