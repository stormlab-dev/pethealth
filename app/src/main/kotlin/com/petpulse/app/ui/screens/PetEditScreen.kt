package com.petpulse.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.petpulse.app.data.PetRepository
import com.petpulse.app.ui.appViewModel
import com.petpulse.app.ui.components.ConfirmDialog
import com.petpulse.app.ui.components.DateField
import com.petpulse.core.model.Pet
import com.petpulse.core.model.Sex
import com.petpulse.core.model.Species
import kotlinx.coroutines.launch

class PetEditViewModel(private val repo: PetRepository, private val petId: Long?) : ViewModel() {
    var form by mutableStateOf(Pet(name = "", species = Species.DOG))
        private set
    var loaded by mutableStateOf(petId == null)
        private set

    init {
        petId?.let { id -> viewModelScope.launch { repo.getPet(id)?.let { form = it }; loaded = true } }
    }

    fun update(transform: (Pet) -> Pet) { form = transform(form) }

    fun save(onSaved: () -> Unit) = viewModelScope.launch { repo.savePet(form); onSaved() }
    fun delete(onDeleted: () -> Unit) = viewModelScope.launch { petId?.let { repo.deletePet(it) }; onDeleted() }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PetEditScreen(petId: Long?, onDone: () -> Unit, onDeleted: () -> Unit) {
    val vm = appViewModel { PetEditViewModel(it.repository, petId) }
    val pet = vm.form
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (petId == null) "Add pet" else "Edit ${pet.name}") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (petId != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
                },
            )
        },
    ) { pad ->
        if (!vm.loaded) return@Scaffold
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(pet.name, { v -> vm.update { it.copy(name = v) } }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Species", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Species.entries.forEach { s ->
                    FilterChip(selected = pet.species == s, onClick = { vm.update { it.copy(species = s) } }, label = { Text(s.label) })
                }
            }
            OutlinedTextField(pet.breed.orEmpty(), { v -> vm.update { it.copy(breed = v) } }, label = { Text("Breed (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Sex", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Sex.entries.forEach { s ->
                    FilterChip(selected = pet.sex == s, onClick = { vm.update { it.copy(sex = s) } }, label = { Text(s.name.lowercase().replaceFirstChar(Char::uppercase)) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Spayed / neutered", Modifier.weight(1f))
                Switch(pet.neutered, { v -> vm.update { it.copy(neutered = v) } })
            }
            DateField("Birthday (approximate is fine)", pet.birthDate, { d -> vm.update { it.copy(birthDate = d) } })
            OutlinedTextField(pet.microchipId.orEmpty(), { v -> vm.update { it.copy(microchipId = v) } }, label = { Text("Microchip number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pet.notes.orEmpty(), { v -> vm.update { it.copy(notes = v) } }, label = { Text("Notes: allergies, conditions, diet") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.save(onDone) }, enabled = pet.name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
    if (confirmDelete) ConfirmDialog(
        "Delete ${pet.name}?", "This removes all of ${pet.name}'s logs, medications and reminders.", "Delete",
        onConfirm = { vm.delete(onDeleted) }, onDismiss = { confirmDelete = false },
    )
}
