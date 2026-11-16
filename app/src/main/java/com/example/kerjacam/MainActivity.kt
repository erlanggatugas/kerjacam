@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.example.kerjacam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = Repo(applicationContext)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App(repo) }
            }
        }
    }
}

sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data class SpaceS(val id: String) : Screen
    data class AlbumS(val spaceId: String, val albumId: String?) : Screen
    data class Viewer(val itemId: String) : Screen
    data class Camera(val spaceId: String, val albumId: String?) : Screen
}

@Composable
fun App(repo: Repo) {
    var stack by remember { mutableStateOf<List<Screen>>(listOf(Screen.Home)) }
    fun push(s: Screen) { stack = stack + s }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
    BackHandler(stack.size > 1) { pop() }

    when (val s = stack.last()) {
        Screen.Home -> HomeScreen(repo, { push(Screen.SpaceS(it)) }, { push(Screen.Search) })
        Screen.Search -> SearchScreen(repo, { pop() }, { sp, al -> push(Screen.AlbumS(sp, al)) }, { push(Screen.Viewer(it)) })
        is Screen.SpaceS -> SpaceScreen(repo, s.id, { pop() }, { push(Screen.AlbumS(s.id, it)) }, { push(Screen.Camera(s.id, null)) })
        is Screen.AlbumS -> AlbumScreen(repo, s.spaceId, s.albumId, { pop() }, { push(Screen.Viewer(it)) }, { push(Screen.Camera(s.spaceId, s.albumId)) })
        is Screen.Viewer -> ViewerScreen(repo, s.itemId) { pop() }
        is Screen.Camera -> CameraScreen(repo, s.spaceId, s.albumId) { pop() }
    }
}

// ───────────────────────── Beranda: daftar Ruang ─────────────────────────

@Composable
fun HomeScreen(repo: Repo, onSpace: (String) -> Unit, onSearch: () -> Unit) {
    var showAdd by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Space?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("KerjaCam") }, actions = {
                IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Cari") }
            })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Ruang baru") }
            )
        }
    ) { pad ->
        if (repo.spaces.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Buat Ruang untuk setiap proyek atau lokasi kerja.\nFoto kerja tersimpan terpisah dari galeri pribadi.",
                    textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp)
                )
            }
        } else {
            LazyColumn(
                Modifier.padding(pad), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(repo.spaces, key = { it.id }) { sp ->
                    val nAlbums = repo.albums.count { it.spaceId == sp.id }
                    val nItems = repo.items.count { it.spaceId == sp.id }
                    Card(Modifier.fillMaxWidth().combinedClickable(onClick = { onSpace(sp.id) }, onLongClick = { toDelete = sp })) {
                        Column(Modifier.padding(16.dp)) {
                            Text(sp.name, style = MaterialTheme.typography.titleMedium)
                            Text("$nAlbums koleksi • $nItems foto", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item { Text("Tekan lama untuk menghapus Ruang", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }

    if (showAdd) TextInputDialog("Ruang baru", "Nama proyek / lokasi", { showAdd = false }) { repo.addSpace(it); showAdd = false }
    toDelete?.let { sp ->
        ConfirmDialog("Hapus Ruang \"${sp.name}\"?", "Semua koleksi dan foto di dalamnya ikut terhapus.",
            { toDelete = null }) { repo.deleteSpace(sp.id); toDelete = null }
    }
}

// ───────────────────────── Ruang: daftar Koleksi ─────────────────────────

@Composable
fun SpaceScreen(
    repo: Repo, spaceId: String, onBack: () -> Unit,
    onAlbum: (String?) -> Unit, onCamera: () -> Unit
) {
    val space = repo.spaces.firstOrNull { it.id == spaceId }
    if (space == null) { LaunchedEffect(Unit) { onBack() }; return }
    var showAdd by remember { mutableStateOf(false) }
    val albums = repo.albums.filter { it.spaceId == spaceId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(space.name) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali") } },
                actions = { IconButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "Koleksi baru") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCamera) { Icon(Icons.Default.PhotoCamera, "Kamera") }
        }
    ) { pad ->
        LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.padding(pad), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "uncat") {
                AlbumCard(repo, "Belum dikategorikan", repo.items.filter { it.spaceId == spaceId && it.albumId == null }) { onAlbum(null) }
            }
            items(albums, key = { it.id }) { a ->
                AlbumCard(repo, a.name, repo.items.filter { it.albumId == a.id }) { onAlbum(a.id) }
            }
        }
    }
    if (showAdd) TextInputDialog("Koleksi baru", "Nama koleksi", { showAdd = false }) { repo.addAlbum(spaceId, it); showAdd = false }
}

@Composable
fun AlbumCard(repo: Repo, title: String, items: List<Item>, onClick: () -> Unit) {
    val cover = items.maxByOrNull { it.createdAt }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (cover != null) AsyncImage(repo.fileOf(cover), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Default.PhotoLibrary, null, Modifier.align(Alignment.Center))
        }
        Column(Modifier.padding(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text("${items.size} item", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ───────────────────────── Koleksi: kisi foto ─────────────────────────

@Composable
fun AlbumScreen(
    repo: Repo, spaceId: String, albumId: String?, onBack: () -> Unit,
    onItem: (String) -> Unit, onCamera: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val album = albumId?.let { id -> repo.albums.firstOrNull { it.id == id } }
    if (albumId != null && album == null) { LaunchedEffect(Unit) { onBack() }; return }
    val title = album?.name ?: "Belum dikategorikan"
    val list = repo.items.filter { it.spaceId == spaceId && it.albumId == albumId }.sortedByDescending { it.createdAt }
    var confirmDelete by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        scope.launch {
            val names = withContext(Dispatchers.IO) { uris.mapNotNull { repo.copyIn(it) } }
            names.forEach { repo.addItem(spaceId, albumId, it) }
            if (names.isNotEmpty()) Toast.makeText(ctx, "${names.size} foto diimpor", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali") } },
                actions = {
                    IconButton(onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Icon(Icons.Default.AddPhotoAlternate, "Impor") }
                    IconButton(onClick = {
                        scope.launch {
                            val n = withContext(Dispatchers.IO) { list.count { repo.exportToGallery(it, title) } }
                            Toast.makeText(ctx, "$n foto diekspor ke galeri", Toast.LENGTH_SHORT).show()
                        }
                    }) { Icon(Icons.Default.Download, "Ekspor ke galeri") }
                    if (album != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Hapus koleksi") }
                }
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onCamera) { Icon(Icons.Default.PhotoCamera, "Kamera") } }
    ) { pad ->
        if (list.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { Text("Belum ada foto") }
        } else {
            LazyVerticalGrid(
                GridCells.Fixed(3), Modifier.padding(pad), contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(list, key = { it.id }) { it ->
                    AsyncImage(
                        repo.fileOf(it), it.note, Modifier.aspectRatio(1f).clickable { onItem(it.id) },
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
    if (confirmDelete) ConfirmDialog("Hapus koleksi \"$title\"?", "Foto di dalamnya dipindah ke Belum dikategorikan.",
        { confirmDelete = false }) { repo.deleteAlbum(albumId!!); confirmDelete = false }
}

// ───────────────────────── Penampil foto + catatan ─────────────────────────

@Composable
fun ViewerScreen(repo: Repo, itemId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val item = repo.items.firstOrNull { it.id == itemId }
    if (item == null) { LaunchedEffect(Unit) { onBack() }; return }
    var note by remember(item.id) { mutableStateOf(item.note) }
    var confirmDelete by remember { mutableStateOf(false) }
    val folder = repo.albums.firstOrNull { it.id == item.albumId }?.name ?: "Belum dikategorikan"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(DateFormat.getDateTimeInstance().format(Date(item.createdAt)), style = MaterialTheme.typography.titleSmall) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali") } },
                actions = {
                    IconButton(onClick = {
                        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", repo.fileOf(item))
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(Intent.createChooser(send, "Bagikan foto"))
                    }) { Icon(Icons.Default.Share, "Bagikan") }
                    IconButton(onClick = {
                        val ok = repo.exportToGallery(item, folder)
                        Toast.makeText(ctx, if (ok) "Diekspor ke galeri" else "Gagal mengekspor", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Default.Download, "Ekspor") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Hapus") }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            AsyncImage(repo.fileOf(item), null, Modifier.weight(1f).fillMaxWidth(), contentScale = ContentScale.Fit)
            OutlinedTextField(
                value = note,
                onValueChange = { note = it; repo.updateNote(item.id, it) },
                label = { Text("Catatan") },
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            )
        }
    }
    if (confirmDelete) ConfirmDialog("Hapus foto ini?", "Tindakan ini tidak bisa dibatalkan.",
        { confirmDelete = false }) { repo.deleteItem(item.id); confirmDelete = false }
}

// ───────────────────────── Kamera kerja (CameraX) ─────────────────────────

@Composable
fun CameraScreen(repo: Repo, spaceId: String, initialAlbum: String?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val owner = ctx as ComponentActivity
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(Manifest.permission.CAMERA) }

    var albumId by remember { mutableStateOf(initialAlbum) }
    var shots by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val albums = repo.albums.filter { it.spaceId == spaceId }

    if (!granted) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Izin kamera diperlukan")
                Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) { Text("Beri izin") }
                TextButton(onClick = onClose) { Text("Kembali") }
            }
        }
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                val previewView = PreviewView(c)
                val future = ProcessCameraProvider.getInstance(c)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                }, ContextCompat.getMainExecutor(c))
                previewView
            }
        )

        // Pilih Koleksi sebelum memotret
        LazyRow(
            Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { FilterChip(selected = albumId == null, onClick = { albumId = null }, label = { Text("Belum dikategorikan") }) }
            items(albums, key = { it.id }) { a ->
                FilterChip(selected = albumId == a.id, onClick = { albumId = a.id }, label = { Text(a.name) })
            }
            item { AssistChip(onClick = { showAdd = true }, label = { Text("+ Koleksi") }) }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onClose) { Text("Selesai", color = Color.White) }
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(Color.White).clickable {
                    val file = File(repo.mediaDir, "${UUID.randomUUID()}.jpg")
                    capture.takePicture(
                        ImageCapture.OutputFileOptions.Builder(file).build(),
                        ContextCompat.getMainExecutor(ctx),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                repo.addItem(spaceId, albumId, file.name); shots++
                            }
                            override fun onError(e: ImageCaptureException) {
                                Toast.makeText(ctx, "Gagal memotret", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            )
            Text("$shots foto", color = Color.White)
        }
    }
    if (showAdd) TextInputDialog("Koleksi baru", "Nama koleksi", { showAdd = false }) {
        albumId = repo.addAlbum(spaceId, it); showAdd = false
    }
}

// ───────────────────────── Pencarian ─────────────────────────

@Composable
fun SearchScreen(
    repo: Repo, onBack: () -> Unit,
    onAlbum: (String, String?) -> Unit, onItem: (String) -> Unit
) {
    var q by remember { mutableStateOf("") }
    val query = q.trim()
    val foundAlbums = if (query.isEmpty()) emptyList()
    else repo.albums.filter { it.name.contains(query, true) || it.description.contains(query, true) }
    val foundItems = if (query.isEmpty()) emptyList() else repo.items.filter { it.note.contains(query, true) }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                OutlinedTextField(q, { q = it }, placeholder = { Text("Cari catatan atau koleksi") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali") } }
        )
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(foundAlbums, key = { "a" + it.id }) { a ->
                val sp = repo.spaces.firstOrNull { it.id == a.spaceId }?.name ?: ""
                Card(Modifier.fillMaxWidth().clickable { onAlbum(a.spaceId, a.id) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(a.name, style = MaterialTheme.typography.titleSmall)
                        Text("Koleksi di $sp", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(foundItems, key = { "i" + it.id }) { it ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onItem(it.id) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(repo.fileOf(it), null, Modifier.size(64.dp), contentScale = ContentScale.Crop)
                    Text(it.note, Modifier.padding(12.dp), maxLines = 2)
                }
            }
            if (query.isNotEmpty() && foundAlbums.isEmpty() && foundItems.isEmpty()) item { Text("Tidak ada hasil") }
        }
    }
}

// ───────────────────────── Dialog bantu ─────────────────────────

@Composable
fun TextInputDialog(title: String, label: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onOk(text) }) { Text("Simpan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onOk) { Text("Hapus") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}
