package com.shibi.orbis.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.shibi.orbis.ui.theme.isDark

internal fun themeCardTag(id: String) = "theme_card_$id"

internal fun themePreviewTag(id: String) = "theme_preview_$id"

/** Settings > Theme: the light/dark choice, then one card per colour theme with a live preview. */
@Composable
fun ThemePickerScreen(
    settings: BrowserSettings,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val darkMode by settings.darkMode.collectAsStateWithLifecycle()
    val themeId by settings.themeId.collectAsStateWithLifecycle()
    ThemePickerContent(
        darkMode = darkMode,
        onDarkMode = settings::setDarkMode,
        selected = AppThemes.byId(themeId),
        onSelect = { settings.setThemeId(it.id) },
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
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 280.dp),
            modifier = Modifier.padding(padding).fillMaxSize().selectableGroup(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.dark_mode_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    DarkModeChoice(darkMode, onDarkMode)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = stringResource(R.string.themes_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                )
            }
            items(AppThemes.all, key = { it.id }) { theme ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeCard(theme, selected = theme.id == selected.id, previewDark = previewDark, onSelect = { onSelect(theme) })
                    AnimatedChoiceSlot(theme)
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
 * Where an animated theme's Animated/Off toggle goes (a later change adds the first animated themes). It is a
 * sibling of the card, not a child, so the card's own accessibility text stays clean. Static themes have none.
 */
@Composable
private fun AnimatedChoiceSlot(theme: AppTheme) {
    if (!theme.animated) return
}

@Composable
private fun ThemeCard(theme: AppTheme, selected: Boolean, previewDark: Boolean, onSelect: () -> Unit) {
    val name = stringResource(theme.nameRes)
    val description = stringResource(theme.descriptionRes)
    val summary = stringResource(R.string.theme_card_description, name, description)
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
            ThemePreview(theme, previewDark)
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
private fun ThemePreview(theme: AppTheme, dark: Boolean) {
    val c = theme.mode(dark).scheme
    Column(
        Modifier
            .fillMaxWidth()
            .height(104.dp)
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .background(c.background)
            .padding(8.dp)
            .clearAndSetSemantics { testTag = themePreviewTag(theme.id) },
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
