package com.shibi.orbis.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shibi.orbis.AppContainer
import com.shibi.orbis.R

/** Settings-only monospace error/debug log. Never shown on main browser chrome. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(container: AppContainer, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: DebugLogViewModel = viewModel(factory = DebugLogViewModel.factory(container.debugLog))
    val version by vm.version.collectAsStateWithLifecycle()
    val lines = remember(version) { vm.lines() }
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val copiedText = stringResource(R.string.debug_copied)
    LaunchedEffect(lines.size, version) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debug_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Orbis error log", vm.copyText()))
                    Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.debug_copy_all)) }
                OutlinedButton(onClick = vm::clear) { Text(stringResource(R.string.debug_clear)) }
            }
            if (lines.isEmpty()) {
                Text(stringResource(R.string.debug_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                    itemsIndexed(lines) { _, line ->
                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
            }
        }
    }
}
