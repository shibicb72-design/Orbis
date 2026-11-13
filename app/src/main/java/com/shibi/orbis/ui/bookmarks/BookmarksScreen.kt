package com.shibi.orbis.ui.bookmarks

import android.content.Context
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.layout.heightIn
import com.shibi.orbis.bookmarks.folderNames
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.BackHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material.icons.filled.Folder
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shibi.orbis.ui.secondaryReadingWidth
import com.shibi.orbis.R
import com.shibi.orbis.bookmarks.Bookmark
import com.shibi.orbis.bookmarks.BookmarkHtml
import com.shibi.orbis.bookmarks.BookmarkManager
import com.shibi.orbis.bookmarks.MAX_IMPORT_BYTES
import com.shibi.orbis.bookmarks.ParsedBookmarks
import com.shibi.orbis.bookmarks.UpdateResult
import com.shibi.orbis.bookmarks.filterBookmarks
import com.shibi.orbis.bookmarks.readCapped
import com.shibi.orbis.engine.parseEditedBookmarkUrl
import com.shibi.orbis.ui.OrbisEmptyState
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What reading an import file gave. Nothing here holds a URL or title that has been logged. */
private sealed interface ImportRead {
    class Parsed(val result: ParsedBookmarks) : ImportRead
    object TooBig : ImportRead
    object Failed : ImportRead
}

/**
 * The saved pages: search, open (here or in a new tab), edit, delete, and HTML export and import. Every row always
 * shows its full address on its own line, so a clever title cannot pass for another site.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(
    manager: BookmarkManager,
    onOpen: (String) -> Unit,
    onOpenInNewTab: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries by manager.entries.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    // The folder being looked at; null is the top level (folders first, then the bookmarks in no folder).
    var openFolder by rememberSaveable { mutableStateOf<String?>(null) }
    val folders = remember(entries) { folderNames(entries) }
    LaunchedEffect(folders) { if (openFolder != null && openFolder !in folders) openFolder = null }
    // A search looks through every folder.
    val shown =
        remember(entries, query, openFolder) {
            val pool = if (query.isNotBlank()) entries else entries.filter { it.folder == (openFolder ?: "") }
            filterBookmarks(pool, query)
        }
    var moving by remember { mutableStateOf<Bookmark?>(null) }
    BackHandler(enabled = openFolder != null) { openFolder = null }
    var editing by remember { mutableStateOf<Bookmark?>(null) }
    var toolbarMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
            if (uri != null) {
                val snapshot = manager.entries.value
                scope.launch { toast(context, if (writeExport(context, uri, snapshot)) R.string.bookmarks_exported else R.string.bookmarks_export_failed) }
            }
        }
    // Configuration-aware resources for the import result text.
    val resources = LocalResources.current
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    when (val read = readImport(context, uri)) {
                        is ImportRead.Parsed -> {
                            val result = manager.importAll(read.result)
                            Toast.makeText(
                                context,
                                resources.getString(R.string.bookmarks_imported, result.added, result.skipped),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        ImportRead.TooBig -> toast(context, R.string.bookmarks_import_too_big)
                        ImportRead.Failed -> toast(context, R.string.bookmarks_import_failed)
                    }
                }
            }
        }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(openFolder ?: stringResource(R.string.bookmarks_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (openFolder != null) openFolder = null else onBack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { toolbarMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.bookmarks_menu))
                        }
                        DropdownMenu(expanded = toolbarMenu, onDismissRequest = { toolbarMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.bookmarks_export)) },
                                enabled = entries.isNotEmpty(),
                                onClick = {
                                    toolbarMenu = false
                                    runCatching { exportLauncher.launch("orbis-bookmarks.html") }
                                        .onFailure { toast(context, R.string.bookmarks_export_failed) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.bookmarks_import)) },
                                onClick = {
                                    toolbarMenu = false
                                    runCatching {
                                        importLauncher.launch(arrayOf("text/html", "text/plain", "application/octet-stream"))
                                    }.onFailure { toast(context, R.string.bookmarks_import_failed) }
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .fillMaxSize()
                .secondaryReadingWidth(),
        ) {
            if (entries.isEmpty()) {
                EmptyState(stringResource(R.string.bookmarks_empty))
                return@Column
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                label = { Text(stringResource(R.string.bookmarks_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.search_clear))
                        }
                    }
                },
            )
            HorizontalDivider()
            val showFolders = openFolder == null && query.isBlank()
            if (shown.isEmpty() && !(showFolders && folders.isNotEmpty())) {
                EmptyState(stringResource(R.string.bookmarks_no_matches))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (showFolders) {
                        items(folders, key = { "folder:$it" }) { name ->
                            val count = entries.count { it.folder == name }
                            ListItem(
                                modifier = Modifier.clickable { openFolder = name },
                                leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                                headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(pluralStringResource(R.plurals.bookmarks_folder_count, count, count)) },
                            )
                        }
                    }
                    items(shown, key = { it.id }) { bookmark ->
                        BookmarkRow(
                            bookmark = bookmark,
                            onOpen = { onOpen(bookmark.url) },
                            onOpenInNewTab = { onOpenInNewTab(bookmark.url) },
                            onEdit = { editing = bookmark },
                            onMove = { moving = bookmark },
                            onDelete = { manager.remove(bookmark.id) },
                        )
                    }
                }
            }
        }
    }

    moving?.let { target ->
        MoveToFolderDialog(
            current = target.folder,
            folders = folders,
            onMove = { folder ->
                manager.moveToFolder(target.id, folder)
                moving = null
            },
            onDismiss = { moving = null },
        )
    }

    editing?.let { target ->
        EditBookmarkDialog(
            bookmark = target,
            onSave = { title, address -> save(manager, target, title, address) },
            onDismiss = { editing = null },
        )
    }
}

/** Null when saved, otherwise the string to show under the address field. */
private fun save(manager: BookmarkManager, target: Bookmark, title: String, address: String): Int? {
    val url = parseEditedBookmarkUrl(address) ?: return R.string.bookmarks_error_invalid
    return when (manager.update(target.id, title, url)) {
        UpdateResult.UPDATED -> null
        UpdateResult.DUPLICATE -> R.string.bookmarks_error_duplicate
        UpdateResult.INVALID -> R.string.bookmarks_error_invalid
        UpdateResult.MISSING -> R.string.bookmarks_error_missing
    }
}

@Composable
private fun EmptyState(text: String) {
    OrbisEmptyState(text, Icons.Filled.Bookmarks)
}

@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    onOpen: () -> Unit,
    onOpenInNewTab: () -> Unit,
    onEdit: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val menuDescription = stringResource(R.string.bookmarks_row_menu, bookmark.title)
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        headlineContent = { Text(bookmark.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(bookmark.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.semantics { contentDescription = menuDescription },
                ) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bookmarks_open_new_tab)) },
                        onClick = {
                            menuOpen = false
                            onOpenInNewTab()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bookmarks_edit)) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bookmarks_move)) },
                        onClick = {
                            menuOpen = false
                            onMove()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bookmarks_delete)) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun EditBookmarkDialog(
    bookmark: Bookmark,
    onSave: (title: String, address: String) -> Int?,
    onDismiss: () -> Unit,
) {
    var title by remember(bookmark.id) { mutableStateOf(bookmark.title) }
    var address by remember(bookmark.id) { mutableStateOf(bookmark.url) }
    var error by remember(bookmark.id) { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookmarks_edit_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.bookmarks_field_title)) },
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = {
                        address = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { message -> { Text(stringResource(message)) } },
                    label = { Text(stringResource(R.string.bookmarks_field_address)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val problem = onSave(title, address)
                    if (problem == null) onDismiss() else error = problem
                },
            ) { Text(stringResource(R.string.bookmarks_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.bookmarks_cancel)) }
        },
    )
}

private fun toast(context: Context, message: Int) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

/** Writes the export to the document the user picked, truncating it. True if it all went through. */
private suspend fun writeExport(context: Context, uri: Uri, items: List<Bookmark>): Boolean =
    try {
        withContext(Dispatchers.IO) {
            val bytes = BookmarkHtml.export(items).toByteArray(Charsets.UTF_8)
            // "wt" truncates: without it a shorter export over an older file would leave its tail behind.
            val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("no output stream")
            out.use { it.write(bytes) }
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A provider can throw anything (SecurityException, IllegalStateException...); the user only needs to know it failed.
        false
    }

/** Reads and parses the picked file off the main thread. The file is size-capped before anything is parsed. */
private suspend fun readImport(context: Context, uri: Uri): ImportRead =
    try {
        withContext(Dispatchers.IO) {
            val stream = context.contentResolver.openInputStream(uri) ?: return@withContext ImportRead.Failed
            val bytes = stream.use { readCapped(it, MAX_IMPORT_BYTES) } ?: return@withContext ImportRead.TooBig
            ImportRead.Parsed(BookmarkHtml.parse(String(bytes, Charsets.UTF_8)))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ImportRead.Failed
    }

/** Files a bookmark: an existing folder, a new one, or none. */
@Composable
private fun MoveToFolderDialog(current: String, folders: List<String>, onMove: (String) -> Unit, onDismiss: () -> Unit) {
    var newName by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookmarks_move)) },
        text = {
            Column {
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    item {
                        ListItem(
                            modifier = Modifier.clickable { onMove("") },
                            headlineContent = { Text(stringResource(R.string.bookmarks_no_folder)) },
                            trailingContent = { if (current.isEmpty()) Icon(Icons.Filled.Check, contentDescription = null) },
                        )
                    }
                    items(folders.size) { i ->
                        val name = folders[i]
                        ListItem(
                            modifier = Modifier.clickable { onMove(name) },
                            leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                            headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingContent = { if (current == name) Icon(Icons.Filled.Check, contentDescription = null) },
                        )
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it.take(com.shibi.orbis.bookmarks.MAX_FOLDER_CHARS) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.bookmarks_new_folder)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = newName.isNotBlank(), onClick = { onMove(newName) }) { Text(stringResource(R.string.bookmarks_create_and_move)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.prompt_cancel)) } },
    )
}
