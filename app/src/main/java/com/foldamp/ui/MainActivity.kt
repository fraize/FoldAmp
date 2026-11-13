package com.foldamp.ui

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.util.TypedValue
import android.view.Choreographer
import android.view.Menu
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.foldamp.library.MediaLibrary
import com.foldamp.library.Track
import com.foldamp.library.formatTime
import com.foldamp.playback.Equalizer
import com.foldamp.playback.PlaybackService
import com.foldamp.playback.PlayerHub
import com.foldamp.playback.SpectrumAnalyzer
import com.foldamp.skin.SkinManager
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : ComponentActivity(), UiHost, FileSource {

    private lateinit var mainWin: MainWindowView
    private lateinit var eqWin: EqWindowView
    private lateinit var playlistWin: PlaylistWindowView
    private lateinit var visWin: VisualizerWindowView
    private lateinit var libraryWin: LibraryWindow
    private lateinit var layout: FoldLayout

    private val miniAnalyzer = SpectrumAnalyzer(1024)
    private val bigAnalyzer = SpectrumAnalyzer(2048)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var ticking = false
    private var lastSlowTick = 0L

    private val prefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }

    private val playerListener = { onPlayerChanged() }
    private val eqListener = { eqWin.invalidate(); Unit }
    private val skinListener = { invalidateAll() }

    private val pickFilesLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addUris(uris)
    }
    private val pickFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) addFolder(uri)
    }
    private val pickSkinLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importSkin(uri)
    }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        libraryWin.onPermissionChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        mainWin = MainWindowView(this, this).apply { analyzer = miniAnalyzer }
        eqWin = EqWindowView(this, this)
        playlistWin = PlaylistWindowView(this, this)
        visWin = VisualizerWindowView(this, this).apply { analyzer = bigAnalyzer; onClose = { togglePlaylistOrVis() } }
        libraryWin = LibraryWindow(this, this)
        layout = FoldLayout(this, mainWin, eqWin, playlistWin, visWin, libraryWin)

        layout.eqShown = prefs.getBoolean("eqShown", false)
        layout.playlistShown = prefs.getBoolean("playlistShown", true)
        layout.coverBottomEq = prefs.getBoolean("coverBottomEq", true)
        mainWin.visMode = prefs.getInt("miniVis", 0)
        visWin.mode = prefs.getInt("bigVis", 0)

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        root.addView(layout, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(layout) { v, insets ->
            val bars = insets.getInsets(
                // The camera cutout is ignored on purpose: windows run edge to edge like the mockups.
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        WindowInsetsControllerCompat(window, layout).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (layout.libraryShown) closeLibrary() else moveTaskToBack(true)
            }
        })

        PlayerHub.ensurePlayer()
        if (savedInstanceState == null) {
            requestStartupPermissions()
            handleIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Binding a controller starts the MediaSessionService so playback survives in the background.
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        PlayerHub.addListener(playerListener)
        Equalizer.addListener(eqListener)
        SkinManager.addListener(skinListener)
        if (hasAudioPermission() && !MediaLibrary.scanned) MediaLibrary.scan(this)
        onPlayerChanged()
    }

    override fun onResume() {
        super.onResume()
        ticking = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onPause() {
        ticking = false
        PlayerHub.savePosition()
        super.onPause()
    }

    override fun onStop() {
        PlayerHub.removeListener(playerListener)
        Equalizer.removeListener(eqListener)
        SkinManager.removeListener(skinListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Folding/unfolding or rotating: we handle it in place so playback state and scroll survive.
        layout.requestLayout()
        invalidateAll()
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!ticking) return
            val active = PlayerHub.isPlaying
            miniAnalyzer.update(19, active)
            mainWin.invalidate()
            if (visWin.visibility == View.VISIBLE) {
                bigAnalyzer.update(visWin.barCount, active)
                visWin.invalidate()
            }
            val now = frameTimeNanos / 1_000_000
            if (now - lastSlowTick > 250) {
                lastSlowTick = now
                if (playlistWin.visibility == View.VISIBLE) playlistWin.invalidate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun onPlayerChanged() {
        playlistWin.onPlayerChanged()
        mainWin.invalidate()
    }

    private fun invalidateAll() {
        listOf(mainWin, eqWin, playlistWin, visWin).forEach { it.invalidate() }
        libraryWin.invalidate()
    }

    private fun relayout() {
        prefs.edit()
            .putBoolean("eqShown", layout.eqShown)
            .putBoolean("playlistShown", layout.playlistShown)
            .putBoolean("coverBottomEq", layout.coverBottomEq)
            .apply()
        layout.requestLayout()
        invalidateAll()
    }

    // ---- UiHost ----

    override fun toggleEq() {
        if (layout.mode == FoldLayout.Mode.COVER_LANDSCAPE) {
            // Cover landscape has room for one window under the player: EQ or playlist.
            layout.coverBottomEq = if (layout.libraryShown) true else !layout.coverBottomEq
        } else {
            layout.eqShown = !layout.eqShown
        }
        layout.libraryShown = false
        relayout()
    }

    override fun togglePlaylist() {
        if (layout.mode == FoldLayout.Mode.COVER_LANDSCAPE) {
            layout.coverBottomEq = if (layout.libraryShown) false else !layout.coverBottomEq
        } else {
            layout.playlistShown = !layout.playlistShown
        }
        layout.libraryShown = false
        relayout()
    }

    private fun togglePlaylistOrVis() {
        // Closing the visualizer just hands its space back to the playlist.
        layout.playlistShown = true
        relayout()
    }

    override fun isEqShown() = layout.isEqVisible()
    override fun isPlaylistShown() = layout.isPlaylistVisible()

    override fun openLibrary() {
        if (!hasAudioPermission()) requestAudioPermission()
        layout.libraryShown = true
        libraryWin.refresh()
        relayout()
    }

    override fun closeLibrary() {
        layout.libraryShown = false
        currentFocus?.let { getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
        relayout()
    }

    override fun showMenu(from: View, x: Float, y: Float, items: List<MenuEntry>) {
        layout.placeAnchor(from, x, y)
        layout.menuAnchor.post {
            val popup = PopupMenu(this, layout.menuAnchor)
            val actions = HashMap<Int, () -> Unit>()
            var nextId = 1
            fun build(menu: Menu, entries: List<MenuEntry>) {
                for (e in entries) {
                    if (e.sub != null) {
                        val sub = menu.addSubMenu(e.title)
                        sub.item.isEnabled = e.enabled
                        build(sub, e.sub)
                    } else {
                        val id = nextId++
                        val item = menu.add(Menu.NONE, id, Menu.NONE, e.title)
                        item.isEnabled = e.enabled
                        e.checked?.let { item.isCheckable = true; item.isChecked = it }
                        e.action?.let { actions[id] = it }
                    }
                }
            }
            build(popup.menu, items)
            popup.setOnMenuItemClickListener { actions[it.itemId]?.invoke(); invalidateAll(); true }
            popup.show()
        }
    }

    override fun mainMenu(from: View, x: Float, y: Float) {
        val current = SkinManager.current.name
        showMenu(from, x, y, listOf(
            MenuEntry("Skins", sub = SkinManager.available().map { name ->
                MenuEntry(name, checked = name == current) { SkinManager.select(name) }
            } + MenuEntry("Import skin (.wsz)…") { pickSkin() }),
            MenuEntry("Play", sub = listOf(
                MenuEntry("Files…") { pickFiles() },
                MenuEntry("Folder…") { pickFolder() },
                MenuEntry("Media library…") { openLibrary() },
            )),
            MenuEntry("Visualization", sub = listOf(
                MenuEntry("Mini: spectrum", checked = mainWin.visMode == 0) { setMiniVis(0) },
                MenuEntry("Mini: oscilloscope", checked = mainWin.visMode == 1) { setMiniVis(1) },
                MenuEntry("Mini: off", checked = mainWin.visMode == 2) { setMiniVis(2) },
                MenuEntry("Large: rainbow blocks", checked = visWin.mode == 0) { setBigVis(0) },
                MenuEntry("Large: skin colors", checked = visWin.mode == 1) { setBigVis(1) },
                MenuEntry("Large: oscilloscope", checked = visWin.mode == 2) { setBigVis(2) },
            )),
            MenuEntry("Equalizer", checked = isEqShown()) { toggleEq() },
            MenuEntry("Playlist editor", checked = isPlaylistShown()) { togglePlaylist() },
            MenuEntry("Shuffle", checked = PlayerHub.shuffle) { PlayerHub.toggleShuffle() },
            MenuEntry("Repeat: " + when (PlayerHub.repeatMode) {
                Player.REPEAT_MODE_ALL -> "all"; Player.REPEAT_MODE_ONE -> "track"; else -> "off"
            }) { PlayerHub.cycleRepeat() },
            MenuEntry("Rescan library") { if (hasAudioPermission()) MediaLibrary.scan(this) else requestAudioPermission() },
            MenuEntry("About FoldAmp…") { about() },
            MenuEntry("Exit") { exit() },
        ))
    }

    private fun setMiniVis(m: Int) { mainWin.visMode = m; prefs.edit().putInt("miniVis", m).apply() }
    private fun setBigVis(m: Int) { visWin.mode = m; prefs.edit().putInt("bigVis", m).apply() }

    override fun minimize() { moveTaskToBack(true) }

    override fun exit() {
        PlayerHub.stop()
        PlayerHub.savePosition()
        stopService(Intent(this, PlaybackService::class.java))
        finishAndRemoveTask()
    }

    override fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun promptText(title: String, initial: String, done: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial); selectAll()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val pad = dp(20)
        val box = FrameLayout(this).apply { setPadding(pad, dp(8), pad, 0); addView(input) }
        AlertDialog.Builder(this, com.foldamp.R.style.Theme_FoldAmp_Dialog)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Save") { _, _ -> done(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }

    override fun showFileInfo(index: Int) {
        val track = PlayerHub.tracks.getOrNull(index) ?: return
        // Read the file itself so tags MediaStore skipped (e.g. year from DATE) still show up.
        MediaLibrary.background({ MediaLibrary.withFileTags(this, track) }) { t -> showFileInfo(index, t) }
    }

    private fun showFileInfo(index: Int, t: Track) {
        val isCurrent = index == PlayerHub.currentIndex
        // Merge in anything ExoPlayer parsed from the file itself while playing.
        val live = if (isCurrent) PlayerHub.player?.mediaMetadata else null
        val fmt = if (isCurrent) PlayerHub.audioFormat else null
        val rows = listOf(
            "Title" to t.title,
            "Artist" to t.artist.ifBlank { live?.artist?.toString().orEmpty() },
            "Album artist" to t.albumArtist.ifBlank { live?.albumArtist?.toString().orEmpty() },
            "Album" to t.album.ifBlank { live?.albumTitle?.toString().orEmpty() },
            "Track" to (t.trackNumber.takeIf { it > 0 } ?: live?.trackNumber)?.toString().orEmpty(),
            "Disc" to (t.discNumber.takeIf { it > 0 } ?: live?.discNumber)?.toString().orEmpty(),
            "Year" to (t.year.takeIf { it > 0 } ?: live?.recordingYear ?: live?.releaseYear)?.toString().orEmpty(),
            "Genre" to t.genre.ifBlank { live?.genre?.toString().orEmpty() },
            "Composer" to t.composer.ifBlank { live?.composer?.toString().orEmpty() },
            "Now playing" to (if (isCurrent && t.isStream) PlayerHub.streamTitle.orEmpty() else ""),
            "Length" to if (t.isStream) "Live stream" else formatTime(t.durationMs.takeIf { it > 0 } ?: if (isCurrent) PlayerHub.durationMs else 0),
            "Format" to (fmt?.sampleMimeType ?: t.mimeType),
            "Bitrate" to (if (isCurrent) PlayerHub.bitrateKbps() else t.bitrateKbps).takeIf { it > 0 }?.let { "$it kbps" }.orEmpty(),
            "Sample rate" to (fmt?.sampleRate?.takeIf { it > 0 }?.let { "$it Hz" }).orEmpty(),
            "Channels" to (fmt?.channelCount?.takeIf { it > 0 }?.let { if (it == 1) "Mono" else if (it == 2) "Stereo" else "$it" }).orEmpty(),
            "Size" to t.sizeBytes.takeIf { it > 0 }?.let { "%.1f MB".format(it / 1048576.0) }.orEmpty(),
            "Comment" to t.comment,
            "File" to t.fileName,
            "Location" to t.path.ifBlank { t.uri },
        ).filter { it.second.isNotBlank() }

        val c = SkinManager.current.playlistColors
        val text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextIsSelectable(true)
            setTextColor(c.normal)
            setBackgroundColor(c.normalBg)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            this.text = rows.joinToString("\n") { (k, v) -> "${k.padEnd(13)}$v" }
        }
        AlertDialog.Builder(this, com.foldamp.R.style.Theme_FoldAmp_Dialog)
            .setTitle("File info")
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Play") { _, _ -> PlayerHub.playAt(index) }
            .show()
    }

    private fun about() {
        AlertDialog.Builder(this, com.foldamp.R.style.Theme_FoldAmp_Dialog)
            .setTitle("FoldAmp 0.1")
            .setMessage(
                "A classic-skinned music player for the Galaxy Z Fold.\n\n" +
                    "Skin: ${SkinManager.current.name}\n" +
                    "Library: ${MediaLibrary.tracks.size} tracks\n\n" +
                    "Decoders: platform codecs (MP3, AAC, FLAC, Vorbis, Opus, WAV, AMR) plus FFmpeg " +
                    "(ALAC, WMA, APE, AC-3, E-AC-3, DTS, TrueHD and more)."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // ---- FileSource ----

    private val audioPermission get() =
        if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    override fun hasAudioPermission() = checkSelfPermission(audioPermission) == PackageManager.PERMISSION_GRANTED

    override fun requestAudioPermission() {
        permissionLauncher.launch(arrayOf(audioPermission))
    }

    private fun requestStartupPermissions() {
        val wanted = buildList {
            if (!hasAudioPermission()) add(audioPermission)
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }

    override fun pickFiles() = pickFilesLauncher.launch(arrayOf("audio/*", "application/ogg", "application/x-flac"))
    override fun pickFolder() = pickFolderLauncher.launch(null)
    override fun pickSkin() = pickSkinLauncher.launch(arrayOf("*/*"))

    private fun addUris(uris: List<Uri>, play: Boolean = false) {
        uris.forEach { runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        toast("Reading ${uris.size} file${if (uris.size == 1) "" else "s"}…")
        MediaLibrary.background({
            uris.filterNot { isPlaylistFile(contentResolver.getType(it).orEmpty(), it.lastPathSegment.orEmpty()) }
                .map { MediaLibrary.readFile(this, it) }
        }) { tracks ->
            if (tracks.isEmpty()) toast("No playable audio files") else PlayerHub.add(tracks, playFirst = play || PlayerHub.tracks.isEmpty())
        }
    }

    private fun addFolder(tree: Uri) {
        runCatching { contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        toast("Scanning folder…")
        MediaLibrary.background({
            val found = ArrayList<Uri>()
            collectAudio(tree, DocumentsContract.getTreeDocumentId(tree), found, 0)
            found.sortBy { it.toString() }
            found.map { MediaLibrary.readFile(this, it) }
        }) { tracks ->
            if (tracks.isEmpty()) toast("No audio files found") else PlayerHub.add(tracks, playFirst = PlayerHub.tracks.isEmpty())
        }
    }

    // Containers Media3 can demux; FFmpeg only supplies decoders, so WMA/APE/WavPack files can't open.
    private val audioExtensions = setOf(
        "mp3", "m4a", "aac", "mp4", "flac", "ogg", "oga", "opus", "wav", "alac",
        "aiff", "aif", "amr", "mka", "ac3", "eac3", "dts", "caf",
    )
    private val playlistExtensions = setOf("m3u", "m3u8", "pls", "cue", "xspf", "wpl", "asx")

    /** Playlist files report audio MIME types (audio/x-mpegurl, audio/x-scpls) but aren't playable. */
    private fun isPlaylistFile(mime: String, name: String) =
        "mpegurl" in mime || "scpls" in mime || name.substringAfterLast('.', "").lowercase() in playlistExtensions

    private fun collectAudio(tree: Uri, docId: String, out: MutableList<Uri>, depth: Int) {
        if (depth > 12) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0); val mime = c.getString(1).orEmpty(); val name = c.getString(2).orEmpty()
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) collectAudio(tree, id, out, depth + 1)
                else if (isPlaylistFile(mime, name)) continue
                else if (mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in audioExtensions) {
                    out += DocumentsContract.buildDocumentUriUsingTree(tree, id)
                }
            }
        }
    }

    private fun importSkin(uri: Uri) {
        var name = "Imported skin"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: name
        }
        val input = contentResolver.openInputStream(uri) ?: return toast("Couldn't open $name")
        SkinManager.import(name, input) { ok -> toast(if (ok) "Skin installed: ${name.removeSuffix(".wsz")}" else "$name isn't a classic Winamp skin") }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val type = intent.type ?: contentResolver.getType(uri).orEmpty()
        val path = uri.lastPathSegment.orEmpty().lowercase()
        if (path.endsWith(".wsz") || type.contains("zip") || type.contains("winamp")) importSkin(uri)
        else addUris(listOf(uri), play = true)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
