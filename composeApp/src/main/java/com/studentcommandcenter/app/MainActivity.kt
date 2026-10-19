package com.studentcommandcenter.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.studentcommandcenter.app.data.AcademicYearDto
import com.studentcommandcenter.app.data.ContainerDto
import com.studentcommandcenter.app.data.ContainerFileDto
import com.studentcommandcenter.app.data.CreateContainerRequest
import com.studentcommandcenter.app.data.DashboardDto
import com.studentcommandcenter.app.data.SccApi
import com.studentcommandcenter.app.data.SecurePrefs
import com.studentcommandcenter.app.data.UserDto
import com.studentcommandcenter.app.data.WalletCredentialDto
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = SecurePrefs(applicationContext)
        setContent { MaterialTheme { SccAnchoredApp(prefs) } }
    }
}

private enum class AppTab { Home, Containers, Wallet, Settings }

@Serializable
private data class PendingUpload(
    val ownerEmail: String,
    val containerId: Long,
    val localPath: String,
    val fileName: String,
    val mime: String,
    val note: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SccAnchoredApp(prefs: SecurePrefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var apiBase by remember { mutableStateOf(prefs.apiBaseUrl) }
    val api = remember(apiBase) { SccApi(apiBase) }
    var token by remember { mutableStateOf(prefs.token) }
    var email by remember { mutableStateOf(prefs.email.orEmpty()) }
    var user by remember { mutableStateOf<UserDto?>(null) }
    var activeTab by remember { mutableStateOf(AppTab.Home) }
    var dashboard by remember { mutableStateOf<DashboardDto?>(null) }
    val containers = remember { mutableStateListOf<ContainerDto>() }
    val wallet = remember { mutableStateListOf<WalletCredentialDto>() }
    var years by remember { mutableStateOf<List<AcademicYearDto>>(emptyList()) }
    var selectedContainerId by remember { mutableStateOf<Long?>(null) }
    var selectedFiles by remember { mutableStateOf<List<ContainerFileDto>>(emptyList()) }
    var pendingUploads by remember { mutableStateOf(loadPending(prefs)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showJwtFor by remember { mutableStateOf<String?>(null) }

    suspend fun reloadData(currentToken: String) {
        try {
            user = api.me(currentToken)
            dashboard = api.dashboard(currentToken)
            containers.clear(); containers.addAll(api.listContainers(currentToken))
            wallet.clear(); wallet.addAll(api.wallet(currentToken))
            years = api.academicYears(currentToken)
            selectedContainerId?.let { id -> selectedFiles = api.listContainerFiles(currentToken, id) }
        } catch (e: Exception) {
            message = e.message ?: "Could not load workspace"
        }
    }

    LaunchedEffect(token, apiBase) {
        val currentToken = token
        if (!currentToken.isNullOrBlank()) {
            busy = true
            reloadData(currentToken)
            busy = false
        }
    }

    suspend fun refresh() { token?.let { reloadData(it) } }

    if (token.isNullOrBlank()) {
        AuthScreen(
            apiBase = apiBase,
            onBaseChange = { apiBase = it; prefs.apiBaseUrl = it },
            busy = busy,
            message = message,
            onMessage = { message = it },
            onBusy = { busy = it },
            onAuth = { authResponse ->
                token = authResponse.token
                prefs.token = authResponse.token
                email = authResponse.user.email
                prefs.email = email
                user = authResponse.user
                activeTab = AppTab.Home
                message = "Welcome, ${authResponse.user.displayName ?: authResponse.user.email}"
            },
            api = api
        )
        return
    }

    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("SCC Anchored", fontWeight = FontWeight.Bold)
                        Text(user?.displayName ?: email, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    if (busy) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    TextButton(onClick = { scope.launch { busy = true; refresh(); busy = false } }) { Text("Sync") }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = activeTab == AppTab.Home, onClick = { activeTab = AppTab.Home }, icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Home") })
                NavigationBarItem(selected = activeTab == AppTab.Containers, onClick = { activeTab = AppTab.Containers }, icon = { Icon(Icons.Filled.FolderOpen, null) }, label = { Text("Folders") })
                NavigationBarItem(selected = activeTab == AppTab.Wallet, onClick = { activeTab = AppTab.Wallet }, icon = { Icon(Icons.Filled.VerifiedUser, null) }, label = { Text("Wallet") })
                NavigationBarItem(selected = activeTab == AppTab.Settings, onClick = { activeTab = AppTab.Settings }, icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            message?.let { notice ->
                Text(notice, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.primary)
            }
            when (activeTab) {
                AppTab.Home -> HomeScreen(dashboard, years, busy,
                    onCreateYear = { name -> scope.launch { try { busy = true; api.createAcademicYear(token!!, name); years = api.academicYears(token!!); message = "Academic year created" } catch (e: Exception) { message = e.message } finally { busy = false } } },
                    onCreateSemester = { yearId, name -> scope.launch { try { busy = true; api.createSemester(token!!, yearId, name); years = api.academicYears(token!!); message = "Semester created" } catch (e: Exception) { message = e.message } finally { busy = false } } },
                    onSetCurrent = { id -> scope.launch { try { api.postCurrentSemester(token!!, id); refresh() } catch (e: Exception) { message = e.message } } }
                )
                AppTab.Containers -> ContainersScreen(
                    context = context,
                    api = api,
                    token = token!!,
                    email = email,
                    containers = containers.toList(),
                    files = selectedFiles,
                    selectedContainerId = selectedContainerId,
                    pendingUploads = pendingUploads.count { upload -> upload.ownerEmail.equals(email, true) },
                    busy = busy,
                    onSelected = { id ->
                        selectedContainerId = id
                        scope.launch {
                            try { selectedFiles = api.listContainerFiles(token!!, id) }
                            catch (e: Exception) { message = e.message }
                        }
                    },
                    onCreate = { kind, name, description ->
                        scope.launch {
                            try {
                                busy = true
                                api.createContainer(token!!, CreateContainerRequest(kind, name, description))
                                containers.clear()
                                containers.addAll(api.listContainers(token!!))
                                message = "$kind folder created"
                            } catch (e: Exception) { message = e.message }
                            finally { busy = false }
                        }
                    },
                    onUpload = { containerId, fileName, mime, bytes, pinToIpfs ->
                        scope.launch {
                            val localFile = persistImportedFile(context, containerId, fileName, bytes)
                            try {
                                busy = true
                                api.uploadContainerFile(token!!, containerId, fileName, mime, bytes, pinToIpfs = pinToIpfs)
                                selectedFiles = api.listContainerFiles(token!!, containerId)
                                containers.clear()
                                containers.addAll(api.listContainers(token!!))
                                message = if (pinToIpfs) "File uploaded; encrypted storage and IPFS pin attempted" else "File uploaded to encrypted server storage"
                            } catch (e: Exception) {
                                val pending = PendingUpload(email, containerId, localFile.absolutePath, fileName, mime)
                                pendingUploads = pendingUploads.filterNot { it.localPath == pending.localPath } + pending
                                persistPending(prefs, pendingUploads)
                                message = "Saved privately on this device; upload queued: ${e.message ?: "server unavailable"}"
                            } finally { busy = false }
                        }
                    },
                    onDownload = { containerId, fileId, fileName ->
                        scope.launch {
                            try {
                                busy = true
                                val bytes = api.downloadContainerFile(token!!, containerId, fileId)
                                val safeName = fileName.substringAfterLast('/').substringAfterLast('\\')
                                    .replace(Regex("[^A-Za-z0-9._() -]"), "_")
                                val target = File(context.filesDir, "downloads/$containerId/$safeName")
                                target.parentFile?.mkdirs()
                                target.writeBytes(bytes)
                                message = "Downloaded copy to app-private storage"
                            } catch (e: Exception) { message = e.message }
                            finally { busy = false }
                        }
                    },
                    onAnchor = { id ->
                        scope.launch {
                            try {
                                busy = true
                                val result = api.anchorContainer(token!!, id)
                                message = "Container anchor: ${result.anchorStatus}"
                                refresh()
                            } catch (e: Exception) { message = e.message }
                            finally { busy = false }
                        }
                    },
                    onDelete = { id ->
                        scope.launch {
                            try {
                                busy = true
                                api.deleteContainer(token!!, id)
                                selectedContainerId = null
                                selectedFiles = emptyList()
                                refresh()
                                message = "Folder deleted"
                            } catch (e: Exception) { message = e.message }
                            finally { busy = false }
                        }
                    },
                    onRetry = {
                        scope.launch {
                            retryPending(
                                context, api, token!!, email, pendingUploads, prefs,
                                update = { pendingUploads = it },
                                status = { message = it },
                                refresh = { id ->
                                    if (selectedContainerId == id) selectedFiles = api.listContainerFiles(token!!, id)
                                    refresh()
                                }
                            )
                        }
                    },
                    onError = { message = it },
                    onBusy = { busy = it }
                )
                AppTab.Wallet -> WalletScreen(wallet.toList(), showJwtFor, onToggleJwt = { showJwtFor = if (showJwtFor == it) null else it })
                AppTab.Settings -> SettingsScreen(apiBase, email, pendingUploads.count { it.ownerEmail.equals(email, true) },
                    onBaseChange = { apiBase = it; prefs.apiBaseUrl = it },
                    onSignOut = {
                        scope.launch {
                            runCatching { api.logout(token!!) }
                            prefs.clearSession(); token = null; user = null; dashboard = null
                            containers.clear(); wallet.clear(); selectedFiles = emptyList(); selectedContainerId = null
                        }
                    },
                    onRetry = { scope.launch { retryPending(context, api, token!!, email, pendingUploads, prefs, { pendingUploads = it }, { message = it }, { id -> if (selectedContainerId == id) selectedFiles = api.listContainerFiles(token!!, id); refresh() }) } }
                )
            }
        }
    }
}

@Composable
private fun AuthScreen(
    apiBase: String,
    onBaseChange: (String) -> Unit,
    busy: Boolean,
    message: String?,
    onMessage: (String?) -> Unit,
    onBusy: (Boolean) -> Unit,
    onAuth: (com.studentcommandcenter.app.data.LoginResponse) -> Unit,
    api: SccApi
) {
    val scope = rememberCoroutineScope()
    var isRegister by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("SCC", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        Text("Academic records, containers and verifiable credentials", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(18.dp))
        Text(if (isRegister) "Create student account" else "Student sign in", style = MaterialTheme.typography.headlineSmall)
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        if (isRegister) OutlinedTextField(name, { name = it }, label = { Text("Display name (optional)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(password, { password = it }, label = { Text("Password (8+ characters)") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), singleLine = true)
        Spacer(Modifier.height(14.dp))
        Button(onClick = {
            scope.launch {
                try {
                    onBusy(true); onMessage(null)
                    val result = if (isRegister) api.register(email.trim(), password, name.trim().ifBlank { null }) else api.login(email.trim(), password)
                    onAuth(result)
                } catch (e: Exception) { onMessage(e.message ?: "Sign-in failed") }
                finally { onBusy(false) }
            }
        }, enabled = !busy && email.isNotBlank() && password.length >= 8, modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(if (isRegister) "Create account" else "Sign in")
        }
        TextButton(onClick = { isRegister = !isRegister; onMessage(null) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (isRegister) "Already have an account? Sign in" else "New student? Create an account")
        }
        Divider(Modifier.padding(vertical = 12.dp))
        Text("Server address", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(apiBase, onBaseChange, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("http://10.0.2.2:4180") })
        Text("Emulator default is 10.0.2.2. On a phone use the server computer's LAN IP or HTTPS domain.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HomeScreen(
    dashboard: DashboardDto?, years: List<AcademicYearDto>, busy: Boolean,
    onCreateYear: (String) -> Unit, onCreateSemester: (Long, String) -> Unit, onSetCurrent: (Long) -> Unit
) {
    var showYearDialog by remember { mutableStateOf(false) }
    var semesterForYear by remember { mutableStateOf<Long?>(null) }
    var newYear by remember { mutableStateOf("") }
    var newSemester by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("Your academic command center", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(dashboard?.currentSemester?.name?.let { "Current semester · $it" } ?: "Choose a semester to get started", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metric("Years", dashboard?.yearCount ?: 0, Modifier.weight(1f)); Metric("Units", dashboard?.unitCount ?: 0, Modifier.weight(1f)); Metric("Open tasks", dashboard?.openTaskCount ?: 0, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metric("Files", dashboard?.fileCount ?: 0, Modifier.weight(1f)); Metric("Containers", dashboard?.containerCount ?: 0, Modifier.weight(1f)); Metric("Credentials", dashboard?.credentialCount ?: 0, Modifier.weight(1f))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showYearDialog = true }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("+ Academic year") }
            OutlinedButton(onClick = { semesterForYear = years.firstOrNull()?.id }, enabled = years.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("+ Semester") }
        }
        if (years.isEmpty()) Text("Create an academic year, then add semester folders in the Folders tab.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        years.forEach { year ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(year.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    year.semesters.forEach { sem ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("• ${sem.name}${if (sem.isCurrent) " · CURRENT" else ""}", modifier = Modifier.weight(1f))
                            if (!sem.isCurrent) TextButton(onClick = { onSetCurrent(sem.id) }) { Text("Set current") }
                        }
                    }
                    TextButton(onClick = { semesterForYear = year.id }) { Text("Add semester to ${year.name}") }
                }
            }
        }
    }
    if (showYearDialog) {
        AlertDialog(onDismissRequest = { showYearDialog = false }, title = { Text("Create academic year") },
            text = { OutlinedTextField(newYear, { newYear = it }, label = { Text("Example: 2026/2027") }) },
            confirmButton = { TextButton(onClick = { if (newYear.isNotBlank()) onCreateYear(newYear); newYear = ""; showYearDialog = false }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { showYearDialog = false }) { Text("Cancel") } })
    }
    if (semesterForYear != null) {
        AlertDialog(onDismissRequest = { semesterForYear = null }, title = { Text("Create semester") },
            text = { Column { OutlinedTextField(newSemester, { newSemester = it }, label = { Text("Example: Semester 1") }); if (years.size > 1) Text("Year: ${years.firstOrNull { it.id == semesterForYear }?.name}") } },
            confirmButton = { TextButton(onClick = { if (newSemester.isNotBlank()) onCreateSemester(semesterForYear!!, newSemester); newSemester = ""; semesterForYear = null }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { semesterForYear = null }) { Text("Cancel") } })
    }
}

@Composable
private fun Metric(label: String, value: Int, modifier: Modifier = Modifier) {
    Card(modifier) { Column(Modifier.padding(10.dp)) { Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(label, style = MaterialTheme.typography.labelSmall) } }
}

@Composable
private fun ContainersScreen(
    context: Context,
    api: SccApi,
    token: String,
    email: String,
    containers: List<ContainerDto>,
    files: List<ContainerFileDto>,
    selectedContainerId: Long?,
    pendingUploads: Int,
    busy: Boolean,
    onSelected: (Long) -> Unit,
    onCreate: (String, String, String?) -> Unit,
    onUpload: (Long, String, String, ByteArray, Boolean) -> Unit,
    onDownload: (Long, Long, String) -> Unit,
    onAnchor: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onRetry: () -> Unit,
    onError: (String) -> Unit,
    onBusy: (Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    var showCreate by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf("semester") }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var pinToIpfs by remember { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf<ContainerDto?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val selected = selectedContainerId
        if (uri == null) return@rememberLauncherForActivityResult
        if (selected == null) { onError("Select a folder first"); return@rememberLauncherForActivityResult }
        scope.launch {
            try {
                onBusy(true)
                val displayName = getDisplayName(context, uri)
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not read selected file")
                require(bytes.size <= 50 * 1024 * 1024) { "file exceeds the 50 MB limit" }
                onUpload(selected, displayName, mime, bytes, pinToIpfs)
            } catch (e: Exception) { onError(e.message ?: "File import failed") }
            finally { onBusy(false) }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Semester & event folders", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("Import files, keep a private app copy, sync and optionally anchor a manifest.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Button(onClick = { showCreate = true }) { Text("+ Folder") }
        }
        if (pendingUploads > 0) {
            Card(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$pendingUploads upload(s) waiting to sync", Modifier.weight(1f))
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }
        }
        if (containers.isEmpty()) {
            Text("No folders yet. Create a semester folder or an event folder, then import a document.", Modifier.padding(top = 26.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(containers, key = { it.id }) { container ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(container.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(container.kind.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            container.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Text("${container.fileCount} files · ${formatBytes(container.totalBytes)} · ${if (container.anchored) "anchored" else "not anchored"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { onSelected(container.id) }) { Text(if (selectedContainerId == container.id) "Selected" else "Open") }
                                TextButton(onClick = { onAnchor(container.id) }, enabled = container.fileCount > 0 && !busy) { Text("Anchor manifest") }
                                TextButton(onClick = { confirmDelete = container }, enabled = !busy) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
        if (selectedContainerId != null) {
            val selected = containers.firstOrNull { it.id == selectedContainerId }
            Divider(Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(selected?.name ?: "Selected folder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Imported files are encrypted on the server and before IPFS pinning.", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Import file") }
            }
            if (files.isEmpty()) Text("No synced files in this folder yet.", Modifier.padding(vertical = 8.dp))
            else LazyColumn(Modifier.weight(0.8f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items(files, key = { it.id }) { file ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) {
                        Text(file.name, fontWeight = FontWeight.SemiBold)
                        Text("${formatBytes(file.size)} · ${file.mime} · SHA-256 ${file.sha256.take(14)}…", style = MaterialTheme.typography.bodySmall)
                        Text(if (file.ipfsCid != null) "IPFS pinned · CID ${file.ipfsCid.take(14)}…" else "Server copy saved · not pinned to IPFS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { onDownload(file.containerId, file.id, file.name) }, enabled = !busy) { Text("Download private copy") }
                    } }
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(onDismissRequest = { showCreate = false }, title = { Text("Create folder") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { kind = "semester" }) { Text(if (kind == "semester") "✓ Semester" else "Semester") }
                    OutlinedButton(onClick = { kind = "event" }) { Text(if (kind == "event") "✓ Event" else "Event") }
                }
                OutlinedTextField(name, { name = it }, label = { Text(if (kind == "semester") "Semester name" else "Event name") }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text("Description (optional)") })
            } },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onCreate(kind, name, description.ifBlank { null }); showCreate = false; name = ""; description = "" }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancel") } })
    }
    confirmDelete?.let { target ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("Delete ${target.name}?") },
            text = { Text("This deletes its server-side file records. Your private device copies remain under app storage.") },
            confirmButton = { TextButton(onClick = { onDelete(target.id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } })
    }
}

@Composable
private fun WalletScreen(wallet: List<WalletCredentialDto>, showJwtFor: String?, onToggleJwt: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Credential wallet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Online-issued credentials are retrieved from the university-backed service. Share only with a trusted verifier.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (wallet.isEmpty()) Text("No active credentials are linked to this account yet.", Modifier.padding(top = 20.dp))
        else LazyColumn(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(wallet, key = { it.credentialId }) { credential ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(credential.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("${credential.issuerName} · ${credential.credentialType}")
                        Text("Status: ${credential.status} · Anchor: ${credential.anchorStatus}", color = MaterialTheme.colorScheme.primary)
                        Text("Certificate: ${credential.credentialId}", style = MaterialTheme.typography.bodySmall)
                        Text("SHA-256: ${credential.digest}", style = MaterialTheme.typography.bodySmall)
                        credential.ipfsCid?.let { Text("Encrypted IPFS CID: $it", style = MaterialTheme.typography.bodySmall) }
                        credential.anchorTxHash?.let { Text("Polygon transaction: $it", style = MaterialTheme.typography.bodySmall) }
                        Row {
                            TextButton(onClick = { onToggleJwt(credential.credentialId) }) { Text(if (showJwtFor == credential.credentialId) "Hide signed VC" else "Reveal signed VC-JWT") }
                            TextButton(onClick = { clipboard.setText(AnnotatedString(credential.signedJwt)) }) { Text("Copy") }
                        }
                        if (showJwtFor == credential.credentialId) Text(credential.signedJwt, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    apiBase: String, email: String, pendingUploads: Int,
    onBaseChange: (String) -> Unit,
    onSignOut: () -> Unit,
    onRetry: () -> Unit
) {
    var base by remember(apiBase) { mutableStateOf(apiBase) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("Signed in", style = MaterialTheme.typography.labelLarge)
            Text(email)
            Text("Session token is stored using Android Keystore-backed encrypted preferences.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("Ktor API address", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(base, { base = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = { onBaseChange(base.trim().trimEnd('/')) }, modifier = Modifier.padding(top = 6.dp)) { Text("Save address") }
        } }
        if (pendingUploads > 0) Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$pendingUploads pending file upload(s)", Modifier.weight(1f)); TextButton(onClick = onRetry) { Text("Retry now") }
        } }
        Text("Device copies are kept in app-private storage. The server encrypts uploaded files before disk/IPFS storage; the IPFS node itself should remain private.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
    }
}

private fun loadPending(prefs: SecurePrefs): List<PendingUpload> =
    runCatching { Json.decodeFromString<List<PendingUpload>>(prefs.pendingUploadsJson()) }.getOrDefault(emptyList())

private fun persistPending(prefs: SecurePrefs, items: List<PendingUpload>) {
    prefs.savePendingUploadsJson(Json.encodeToString(items))
}

private suspend fun retryPending(
    context: Context, api: SccApi, token: String, email: String, pending: List<PendingUpload>, prefs: SecurePrefs,
    update: (List<PendingUpload>) -> Unit, status: (String) -> Unit, refresh: suspend (Long) -> Unit
) {
    val owned = pending.filter { it.ownerEmail.equals(email, true) }
    if (owned.isEmpty()) { status("No pending uploads for this account"); return }
    var remaining = pending.toMutableList()
    var sent = 0
    for (item in owned) {
        try {
            val file = File(item.localPath)
            if (!file.exists()) { remaining.remove(item); continue }
            api.uploadContainerFile(token, item.containerId, item.fileName, item.mime, file.readBytes(), item.note, true)
            remaining.remove(item); sent++
            refresh(item.containerId)
        } catch (_: Exception) { /* keep queued; retry when online */ }
    }
    update(remaining)
    persistPending(prefs, remaining)
    status(if (sent > 0) "$sent queued upload(s) synced" else "Still offline; upload queue retained")
}

private fun persistImportedFile(context: Context, containerId: Long, name: String, bytes: ByteArray): File {
    val dir = File(context.filesDir, "imports/$containerId").apply { mkdirs() }
    val safe = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._() -]"), "_").take(200)
    return File(dir, "${System.currentTimeMillis()}-$safe").apply { writeBytes(bytes) }
}

private fun getDisplayName(context: Context, uri: Uri): String {
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "academic-file"
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0) name = cursor.getString(column) ?: name
        }
    }
    return name
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
