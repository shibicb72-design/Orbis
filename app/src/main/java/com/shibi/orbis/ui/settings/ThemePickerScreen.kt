package com.shibi.orbis.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shibi.orbis.R
import com.shibi.orbis.settings.BrowserSettings
import com.shibi.orbis.settings.DarkMode
import com.shibi.orbis.ui.theme.AppTheme
import com.shibi.orbis.ui.theme.AppThemes
import com.shibi.orbis.ui.theme.MAX_PARTICLES
import com.shibi.orbis.ui.theme.SceneLayer
import com.shibi.orbis.ui.theme.SceneSurface
import com.shibi.orbis.ui.theme.SceneRegion
import com.shibi.orbis.ui.theme.isDark
import com.shibi.orbis.ui.theme.rememberMotionEnabled
import com.shibi.orbis.ui.theme.rememberSceneSurface
import com.shibi.orbis.ui.theme.sceneSurfaceRoot

internal fun themeCardTag(id: String) = "theme_card_$id"

internal fun themePreviewTag(id: String) = "theme_preview_$id"

/** Tests only: told when a card or a preview's scene surface is composed. Null in the app. */
internal interface PickerProbe {
    fun cardComposed(id: String)

    fun previewSurface(id: String, surface: SceneSurface?)
}

internal val LocalPickerProbe = staticCompositionLocalOf<PickerProbe?> { null }

/** Each animated theme's preview draws at most this many scene elements, so all of them together stay under the cap. */
internal val PREVIEW_BUDGET = MAX_PARTICLES / AppThemes.all.count { it.animated }

/** Settings > Theme: the light/dark choice, then one card per colour theme with a live preview. */
@Composable
fun ThemePickerScreen(
    settings: BrowserSettings,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val darkMode by settings.darkMode.collectAsStateWithLifecycle()
    val themeId by settings.themeId.collectAsStateWithLifecycle()
    val staticIds by settings.staticThemeIds.collectAsStateWithLifecycle()
    ThemePickerContent(
        darkMode = darkMode,
        onDarkMode = settings::setDarkMode,
        selected = AppThemes.byId(themeId),
        onSelect = { settings.setThemeId(it.id) },
        staticIds = staticIds,
        onAnimated = { theme, on -> settings.setThemeAnimated(theme.id, on) },
        onBack = onBack,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThemePickerContent(
    darkMode: DarkMode,
    onDarkMode: (DarkMode) -> Unit,
    selected: AppTheme,
    onSelect: (AppTheme) -> Unit,
    staticIds: Set<String>,
    onAnimated: (AppTheme, Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val previewDark = darkMode.isDark(isSystemInDarkTheme())
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.themes_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        // Everything scrolls together. Only the cards are a selectable group: the dark mode choice is a separate setting.
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.dark_mode_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                DarkModeChoice(darkMode, onDarkMode)
            }
            Text(
                text = stringResource(R.string.themes_section_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp).semantics { heading() },
            )
            BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
                // As many columns as fit at least 280.dp each: one on a phone, three or four on a wide screen.
                val columns = ((maxWidth + 12.dp) / (280.dp + 12.dp)).toInt().coerceAtLeast(1)
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppThemes.all.chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { theme ->
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ThemeCard(
                                        theme,
                                        selected = theme.id == selected.id,
                                        previewDark = previewDark,
                                        animated = theme.id !in staticIds,
                                        onSelect = { onSelect(theme) },
                                    )
                                    AnimatedChoiceSlot(theme, animated = theme.id !in staticIds, onAnimated = { onAnimated(theme, it) })
                                }
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DarkModeChoice(current: DarkMode, onSelect: (DarkMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        DarkMode.entries.forEachIndexed { index, mode ->
            val label = stringResource(darkModeLabel(mode))
            val description = stringResource(R.string.dark_mode_option_description, label)
            SegmentedButton(
                selected = mode == current,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, DarkMode.entries.size),
                modifier = Modifier.semantics { contentDescription = description },
            ) {
                Text(label, maxLines = 1)
            }
        }
    }
}

/**
 * An animated theme's Animated/Off toggle. It is a sibling of the card, not a child, so the card's own accessibility
 * text stays clean, and changing it does not select the theme. Static themes have none.
 */
@Composable
private fun AnimatedChoiceSlot(theme: AppTheme, animated: Boolean, onAnimated: (Boolean) -> Unit) {
    if (!theme.animated) return
    val name = stringResource(theme.nameRes)
    val options =
        listOf(
            Triple(true, stringResource(R.string.theme_animation_animated), stringResource(R.string.theme_animation_animated_description, name)),
            Triple(false, stringResource(R.string.theme_animation_off), stringResource(R.string.theme_animation_off_description, name)),
        )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (on, label, description) ->
            SegmentedButton(
                selected = animated == on,
                onClick = { onAnimated(on) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                modifier = Modifier.semantics { contentDescription = description },
            ) {
                Text(label, maxLines = 1)
            }
        }
    }
    if (!rememberMotionEnabled()) {
        Text(
            text = stringResource(R.string.theme_animation_system_off),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ThemeCard(theme: AppTheme, selected: Boolean, previewDark: Boolean, animated: Boolean, onSelect: () -> Unit) {
    val name = stringResource(theme.nameRes)
    val description = stringResource(theme.descriptionRes)
    val summary = stringResource(R.string.theme_card_description, name, description)
    LocalPickerProbe.current?.let { probe -> SideEffect { probe.cardComposed(theme.id) } }
    val state = stringResource(if (selected) R.string.theme_state_selected else R.string.theme_state_not_selected)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border =
            BorderStroke(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(themeCardTag(theme.id))
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .semantics {
                    contentDescription = summary
                    stateDescription = state
                },
    ) {
        Column {
            ThemePreview(theme, previewDark, animated)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                // A check as well as the border, so the choice is not shown by colour alone.
                if (selected) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                } else {
                    RadioButton(selected = false, onClick = null)
                }
            }
        }
    }
}

/**
 * A miniature of the browser drawn in the theme's own colours: toolbar, a selected tab, a private tab (so the
 * private look is visible on every card), a normal tab and a start page area. Decorative: the card text is read.
 */
@Composable
private fun ThemePreview(theme: AppTheme, dark: Boolean, animated: Boolean) {
    val c = theme.mode(dark).scheme
    // Null for a static theme, or with no scene host. The preview follows its own theme's toggle, not the selected theme.
    val surface = rememberSceneSurface(theme = theme, userAnimated = animated, dark = dark, budget = PREVIEW_BUDGET)
    LocalPickerProbe.current?.let { probe -> SideEffect { probe.previewSurface(theme.id, surface) } }
    Box(
        Modifier
            .fillMaxWidth()
            .height(104.dp)
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .background(c.background)
            .sceneSurfaceRoot(surface)
            .clearAndSetSemantics { testTag = themePreviewTag(theme.id) },
    ) {
        SceneLayer(surface, SceneRegion.Preview, Modifier.matchParentSize())
        Column(
            Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(13.dp)).background(c.surfaceContainer).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(c.surfaceContainerHigh))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(14.dp).border(1.5.dp, c.onSurface, RoundedCornerShape(3.dp)))
            }
            Row(Modifier.fillMaxWidth().height(24.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val chip = RoundedCornerShape(8.dp)
                Box(
                    Modifier.weight(1f).fillMaxSize().clip(chip).background(c.secondaryContainer).border(1.dp, c.primary, chip).padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(Modifier.fillMaxWidth(0.6f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.onSecondaryContainer))
                }
                Row(
                    Modifier.weight(1f).fillMaxSize().clip(chip).background(c.tertiaryContainer).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.VisibilityOff, contentDescription = null, tint = c.onTertiaryContainer, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.fillMaxWidth(0.6f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.onTertiaryContainer))
                }
                Box(
                    Modifier.weight(1f).fillMaxSize().clip(chip).background(c.surfaceContainer).padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(Modifier.fillMaxWidth(0.6f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.onSurface))
                }
            }
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(48.dp).height(16.dp).clip(RoundedCornerShape(8.dp)).background(c.primary), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(24.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.onPrimary))
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.width(88.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.onBackground))
                    Box(Modifier.width(56.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.onSurfaceVariant))
                }
            }
        }
    }
}
