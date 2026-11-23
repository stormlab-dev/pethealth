package com.petpulse.app.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.petpulse.app.AppContainer
import com.petpulse.app.data.MediaEntity
import com.petpulse.app.media.MediaLibrary
import com.petpulse.app.ui.appViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MediaViewModel(private val c: AppContainer, val petId: Long) : ViewModel() {
    val items = c.media.forPet(petId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val total = c.media.count().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val customer = c.billing.state
    val library: MediaLibrary get() = c.media
    var message by mutableStateOf<Pair<String, Boolean>?>(null)
    var busy by mutableStateOf(false); private set

    fun add(uris: List<android.net.Uri>) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        busy = true
        message = when (val r = c.media.add(petId, uris, customer.value.plan)) {
            is MediaLibrary.AddResult.Added -> null
            is MediaLibrary.AddResult.Blocked -> r.reason to (r.suggested != customer.value.plan)
            is MediaLibrary.AddResult.Failed -> r.message to false
        }
        busy = false
    }
    fun delete(m: MediaEntity) = viewModelScope.launch { c.media.delete(m) }
}

@Composable
fun MediaTab(petId: Long, petName: String, onPlans: () -> Unit) {
    val vm = appViewModel { MediaViewModel(it, petId) }
    val items by vm.items.collectAsStateWithLifecycle()
    val total by vm.total.collectAsStateWithLifecycle()
    val customer by vm.customer.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<MediaEntity?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { vm.add(it) }
    val limit = customer.plan.limits.maxMedia

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
            Text(
                if (limit != null) "$total of $limit on the free plan · clips up to ${customer.plan.limits.maxClipSeconds}s"
                else "Unlimited photos and videos",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            Button(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }, enabled = !vm.busy) {
                Icon(Icons.Filled.AddAPhoto, null); Spacer(Modifier.width(6.dp)); Text(if (vm.busy) "Adding…" else "Add")
            }
        }
        vm.message?.let { (text, upgrade) ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text, Modifier.weight(1f))
                    if (upgrade) TextButton(onClick = onPlans) { Text("See plans") }
                }
            }
        }
        if (items.isEmpty()) {
            Text("No photos yet. Add a few of $petName, or a health photo to show your vet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
        } else {
            LazyVerticalGrid(GridCells.Adaptive(110.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(items, key = { it.id }) { m -> MediaThumb(vm.library, m) { open = m } }
            }
        }
    }

    open?.let { m ->
        val ctx = LocalContext.current
        AlertDialog(
            onDismissRequest = { open = null },
            text = {
                Column {
                    MediaThumb(vm.library, m, size = 1080, modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {}
                    if (m.kind == "VIDEO") TextButton(onClick = {
                        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", vm.library.file(m))
                        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, m.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }) { Text("Play video (${m.durationSec ?: 0}s)") }
                }
            },
            confirmButton = { Button(onClick = { vm.library.share(listOf(m), petName) }) { Text("Share") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.delete(m); open = null }) { Text("Delete") }
                    TextButton(onClick = { open = null }) { Text("Close") }
                }
            },
        )
    }
}

@Composable
private fun MediaThumb(lib: MediaLibrary, m: MediaEntity, size: Int = 360, modifier: Modifier = Modifier.aspectRatio(1f), onClick: () -> Unit) {
    val bmp by produceState<Bitmap?>(null, m.id, size) { value = lib.thumbnail(m, size) }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick)) {
        bmp?.let { Image(it.asImageBitmap(), contentDescription = m.caption, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (m.kind == "VIDEO") Icon(Icons.Filled.PlayCircle, "Video", tint = Color.White, modifier = Modifier.align(Alignment.Center).size(36.dp))
    }
}
