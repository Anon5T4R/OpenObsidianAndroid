package com.openobsidian.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openobsidian.android.R
import com.openobsidian.android.data.RecentVault
import com.openobsidian.android.data.VaultRepository
import kotlinx.coroutines.launch

@Composable
fun WelcomeScreen(repo: VaultRepository) {
    val scope    = rememberCoroutineScope()
    val recents  by repo.recentVaults.collectAsState(initial = emptyList())

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) scope.launch { repo.acceptPickedVault(uri) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "",                        // hexagon logo
            style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Light),
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.welcome_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = { picker.launch(null) },
            modifier = Modifier.fillMaxWidth(0.7f).heightIn(min = 48.dp)
        ) {
            Icon(Icons.Default.Folder, contentDescription = null)
            Spacer(Modifier.height(0.dp))
            Text("  ${stringResource(R.string.open_vault)}")
        }

        if (recents.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.recent_vaults),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(0.85f)
            ) {
                items(recents, key = { it.uri }) { vault ->
                    RecentVaultRow(
                        vault = vault,
                        onOpen = { scope.launch { repo.setLastVault(vault.uri) } },
                        onForget = { scope.launch { repo.forgetVault(vault.uri) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentVaultRow(vault: RecentVault, onOpen: () -> Unit, onForget: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        onClick = onOpen
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(0.dp))
            Text(
                text = "  ${vault.displayName}",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onForget) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.forget_vault))
            }
        }
    }
}
