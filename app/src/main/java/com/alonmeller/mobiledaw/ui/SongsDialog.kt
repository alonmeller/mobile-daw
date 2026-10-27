package com.alonmeller.mobiledaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alonmeller.mobiledaw.DawViewModel
import com.alonmeller.mobiledaw.data.ProjectInfo
import java.text.DateFormat
import java.util.Date

/** "My Songs", like GarageBand's song browser: open, start, rename, copy and delete songs. */
@Composable
fun SongsDialog(vm: DawViewModel) {
    var renaming by remember { mutableStateOf<ProjectInfo?>(null) }
    var deleting by remember { mutableStateOf<ProjectInfo?>(null) }
    Dialog(onDismissRequest = vm::closeSongs, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.9f)
                .clip(RoundedCornerShape(16.dp))
                .background(DawColors.Panel)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "My Songs",
                    color = DawColors.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (vm.loading) {
                    Text("Opening…", color = DawColors.TextSecondary, fontSize = 13.sp)
                    Spacer(Modifier.width(12.dp))
                }
                PillButton("Done", DawColors.PanelRaised, onClick = vm::closeSongs)
            }
            Spacer(Modifier.height(12.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(180.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                item(key = "new") { NewSongTile(enabled = !vm.loading, onClick = vm::newSong) }
                items(vm.songs, key = { it.id }) { song ->
                    SongTile(
                        song,
                        isOpen = song.id == vm.currentSongId,
                        enabled = !vm.loading,
                        onOpen = { vm.openSong(song.id) },
                        onRename = { renaming = song },
                        onDuplicate = { vm.duplicateSong(song.id) },
                        onDelete = { deleting = song },
                    )
                }
            }
        }

        renaming?.let { song ->
            var text by remember(song.id) { mutableStateOf(song.name) }
            AlertDialog(
                onDismissRequest = { renaming = null },
                title = { Text("Rename song") },
                text = { OutlinedTextField(value = text, onValueChange = { text = it.take(60) }, singleLine = true) },
                confirmButton = {
                    TextButton(onClick = {
                        renaming = null
                        vm.renameSong(song.id, text)
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
            )
        }
        deleting?.let { song ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text("Delete \"${song.name}\"?") },
                text = { Text("The song and its recordings are deleted from the phone for good.\nהשיר וההקלטות שלו יימחקו מהטלפון לתמיד.") },
                confirmButton = {
                    TextButton(onClick = {
                        deleting = null
                        vm.deleteSong(song.id)
                    }) { Text("Delete", color = DawColors.Record) }
                },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
            )
        }
    }
}

private val TileHeight = 128.dp

@Composable
private fun NewSongTile(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(TileHeight)
            .clip(RoundedCornerShape(12.dp))
            .border(2.dp, DawColors.PanelRaised, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("+", color = DawColors.Accent, fontSize = 34.sp, fontWeight = FontWeight.Light)
            Text("New song", color = DawColors.TextPrimary, fontSize = 14.sp)
        }
    }
}

@Composable
private fun SongTile(
    song: ProjectInfo,
    isOpen: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val color = DawColors.track(song.id.hashCode())
    Column(
        Modifier
            .fillMaxWidth()
            .height(TileHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(DawColors.Background)
            .border(2.dp, if (isOpen) DawColors.Accent else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onOpen)
    ) {
        // A colored band with a wave, standing in for the song's artwork.
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(color.copy(alpha = 0.3f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("♫", color = color, fontSize = 24.sp, modifier = Modifier.padding(start = 12.dp))
            Spacer(Modifier.weight(1f))
            if (isOpen) {
                Text(
                    "Open now",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(DawColors.Accent)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Box {
                Box(
                    Modifier
                        .size(40.dp)
                        .clickable(enabled = enabled) { menu = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⋯", color = DawColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem("Rename") {
                        menu = false
                        onRename()
                    }
                    MenuItem("Duplicate") {
                        menu = false
                        onDuplicate()
                    }
                    MenuItem("Delete") {
                        menu = false
                        onDelete()
                    }
                }
            }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                song.name,
                color = DawColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(song.modified)),
                color = DawColors.TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
            )
            Text(length(song.seconds), color = DawColors.TextSecondary, fontSize = 12.sp, maxLines = 1)
        }
    }
}

private fun length(seconds: Double): String {
    if (seconds < 0.5) return "Empty"
    val s = seconds.toLong().coerceAtLeast(1)
    return "%d:%02d".format(s / 60, s % 60)
}
