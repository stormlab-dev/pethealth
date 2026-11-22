package com.petpulse.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.petpulse.app.data.PetRepository
import com.petpulse.app.ui.appViewModel
import com.petpulse.app.ui.components.EmptyState
import com.petpulse.app.ui.components.speciesEmoji
import com.petpulse.core.health.PetAge
import com.petpulse.core.model.Pet
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import com.petpulse.app.util.today

class PetListViewModel(repo: PetRepository) : ViewModel() {
    val pets = repo.pets.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PetListScreen(onOpen: (Long) -> Unit, onAdd: () -> Unit) {
    val vm = appViewModel { PetListViewModel(it.repository) }
    val pets by vm.pets.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { TopAppBar(title = { Text("My pets") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add pet") })
        },
    ) { pad ->
        val list = pets
        when {
            list == null -> Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> EmptyState("Add your first pet to start tracking symptoms, weight, vaccines and medications.", Modifier.padding(pad))
            else -> LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(list, key = { it.id }) { PetRow(it) { onOpen(it.id) } }
            }
        }
    }
}

@Composable
private fun PetRow(pet: Pet, onClick: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(speciesEmoji(pet.species), fontSize = 36.sp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(pet.name, style = MaterialTheme.typography.titleMedium)
                val details = listOfNotNull(pet.breed ?: pet.species.label, PetAge.label(pet.birthDate, today()))
                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
