package com.ahif.lumen.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahif.lumen.data.model.LibraryItem
import com.ahif.lumen.data.reader.OfficeCrypto
import com.ahif.lumen.data.reader.OfficeUnlock
import kotlinx.coroutines.launch

private sealed interface GateState {
    data object Checking : GateState
    data class Locked(val bytes: ByteArray, val error: String? = null, val checking: Boolean = false) : GateState
    data class Open(val item: LibraryItem) : GateState
    data class Failed(val message: String) : GateState
}

/**
 * Shows [content] straight away for normal Office files. For password-protected ones it
 * asks for the password first, then shows [content] with a temporary decrypted copy
 * (wiped when the reader closes). Share still sends the original, protected file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtectedOfficeGate(
    item: LibraryItem,
    onBack: () -> Unit,
    content: @Composable (LibraryItem) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val unlockVm: ReaderUnlockViewModel = viewModel()
    val docId = item.document.id
    var state by remember(docId) { mutableStateOf<GateState>(GateState.Checking) }

    fun tryPassword(bytes: ByteArray, password: String) {
        state = GateState.Locked(bytes, checking = true)
        scope.launch {
            state = try {
                val uri = OfficeUnlock.unlock(context, item.document, bytes, password)
                unlockVm.passwords[docId] = password
                GateState.Open(item.copy(document = item.document.copy(readUri = uri)))
            } catch (_: OfficeCrypto.WrongPasswordException) {
                unlockVm.passwords.remove(docId)
                GateState.Locked(bytes, error = "Wrong password. Try again.")
            } catch (e: OfficeCrypto.UnsupportedEncryptionException) {
                GateState.Failed("This file uses a kind of protection AR Reader can't open yet.")
            } catch (e: Exception) {
                GateState.Failed("Couldn't open this file.")
            }
        }
    }

    LaunchedEffect(docId) {
        state = when (val check = OfficeUnlock.check(context, item.document)) {
            OfficeUnlock.Check.Plain -> GateState.Open(item)
            OfficeUnlock.Check.TooLarge -> GateState.Failed("This protected file is too large to open.")
            is OfficeUnlock.Check.Protected -> {
                // Same reader still open (e.g. after rotating the phone): don't ask again.
                unlockVm.passwords[docId]?.let { tryPassword(check.bytes, it); return@LaunchedEffect }
                GateState.Locked(check.bytes)
            }
        }
    }

    val unlockedUri = (state as? GateState.Open)?.item?.document?.readUri
    DisposableEffect(unlockedUri) {
        onDispose { unlockedUri?.let { OfficeUnlock.wipe(context, it) } }
    }

    when (val s = state) {
        is GateState.Open -> content(s.item)
        else -> Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(item.document.title, maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                when (s) {
                    GateState.Checking -> CircularProgressIndicator()
                    is GateState.Locked -> PasswordPrompt(
                        title = item.document.title,
                        error = s.error,
                        checking = s.checking,
                        onSubmit = { tryPassword(s.bytes, it) }
                    )
                    is GateState.Failed -> Text(
                        s.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp)
                    )
                    is GateState.Open -> Unit
                }
            }
        }
    }
}
