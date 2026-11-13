package com.foldamp.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.MimeTypes
import com.foldamp.library.MediaLibrary
import com.foldamp.library.RadioBrowser
import com.foldamp.library.Track
import com.foldamp.library.formatTime
import com.foldamp.playback.PlayerHub
import com.foldamp.skin.SkinManager

/**
 * Media library browser: search plus Songs / Artists / Albums / Genres / Playlists / Radio tabs,
 * with drill-down. Lives inside a gen.bmp frame and takes its colors from the skin's pledit.txt.
 */
@SuppressLint("ViewConstructor")
class LibraryWindow(context: Context, private val host: UiHost) : FrameLayout(context) {

    private enum class Tab(val label: String) { SONGS("Songs"), ARTISTS("Artists"), ALBUMS("Albums"), GENRES("Genres"), PLAYLISTS("Playlists"), RADIO("Radio") }

    private data class Row(
        val label: String,
        val detail: String = "",
        val right: String = "",
        val onClick: () -> Unit,
        val onLongClick: (() -> Unit)? = null,
    )

    private val frame = GenWindowView(context, host, "MEDIA LIBRARY").apply { onClose = { host.closeLibrary() } }
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val search = EditText(context)
    private val tabRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val crumb = TextView(context)
    private val list = RecyclerView(context)
    private val empty = TextView(context)
    private val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val adapter = RowAdapter()

    private var tab = Tab.SONGS
    /** When drilled into a group, the songs shown and a title for the breadcrumb. */
    private var drill: Pair<String, List<Track>>? = null
    private var shownSongs: List<Track> = emptyList()

    // Radio tab: the directory is queried as the search text settles.
    private var stations: List<Track>? = null
    private var stationsQuery: String? = null
    private var stationsError = false
    private var stationsLoading = false
    private val radioSearch = Runnable { loadStations() }

    var scale: Float
        get() = frame.scale
        set(v) { frame.scale = v }

    private val dp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics)
    private val libListener = { refresh() }
    private val skinListener = { applyColors(); refresh() }

    init {
        addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(content)

        search.apply {
            hint = "Search title, artist, album, genre…"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            textSize = 14f
            setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (tab == Tab.RADIO) { removeCallbacks(radioSearch); postDelayed(radioSearch, 450) }
                    refresh()
                }
            })
            setOnEditorActionListener { v, _, _ ->
                context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0); true
            }
        }
        content.addView(search, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        Tab.entries.forEach { t ->
            val b = button(t.label) {
                tab = t; drill = null
                if (t == Tab.RADIO && stationsQuery != search.text.toString().trim()) loadStations()
                refresh()
            }
            // Six tabs share a cover-screen width, so labels shrink rather than truncate.
            b.setAutoSizeTextTypeUniformWithConfiguration(8, 12, 1, TypedValue.COMPLEX_UNIT_SP)
            tabRow.addView(b, LinearLayout.LayoutParams(0, (34 * dp).toInt(), 1f))
        }
        content.addView(tabRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        crumb.apply {
            textSize = 13f
            setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
            setOnClickListener { drill = null; refresh() }
        }
        content.addView(crumb)

        val listBox = FrameLayout(context)
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        list.isVerticalScrollBarEnabled = true
        listBox.addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        empty.apply {
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
        }
        listBox.addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        content.addView(listBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        actions.addView(button("Play all") { playAll(false) }, LinearLayout.LayoutParams(0, (36 * dp).toInt(), 1f))
        actions.addView(button("Enqueue all") { playAll(true) }, LinearLayout.LayoutParams(0, (36 * dp).toInt(), 1f))
        actions.addView(button("Files…") { (host as? FileSource)?.pickFiles() }, LinearLayout.LayoutParams(0, (36 * dp).toInt(), 1f))
        actions.addView(button("Rescan") { MediaLibrary.scan(context) }, LinearLayout.LayoutParams(0, (36 * dp).toInt(), 1f))
        content.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        applyColors()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        MediaLibrary.addListener(libListener)
        SkinManager.addListener(skinListener)
        val fs = host as? FileSource
        if (fs?.hasAudioPermission() == true && !MediaLibrary.scanned) MediaLibrary.scan(context)
        refresh()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(radioSearch)
        MediaLibrary.removeListener(libListener)
        SkinManager.removeListener(skinListener)
        super.onDetachedFromWindow()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        frame.layout(0, 0, right - left, bottom - top)
        content.measure(
            MeasureSpec.makeMeasureSpec((frame.clientRight - frame.clientLeft).toInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((frame.clientBottom - frame.clientTop).toInt(), MeasureSpec.EXACTLY),
        )
        content.layout(frame.clientLeft.toInt(), frame.clientTop.toInt(), frame.clientRight.toInt(), frame.clientBottom.toInt())
    }

    fun onPermissionChanged() {
        if ((host as? FileSource)?.hasAudioPermission() == true) MediaLibrary.scan(context)
        refresh()
    }

    // ---- Styling ----

    private fun colors() = SkinManager.current.playlistColors

    private fun button(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        isAllCaps = true
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); onClick() }
    }

    private fun applyColors() {
        val c = colors()
        content.setBackgroundColor(c.normalBg)
        search.setTextColor(c.current)
        search.setHintTextColor(withAlpha(c.normal, 0x90))
        search.background = GradientDrawable().apply {
            setColor(blend(c.normalBg, c.normal, 0.08f)); setStroke((1 * dp).toInt().coerceAtLeast(1), withAlpha(c.normal, 0x80))
        }
        crumb.setTextColor(c.current)
        crumb.setBackgroundColor(blend(c.normalBg, c.selectedBg, 0.5f))
        empty.setTextColor(c.normal)
        for (row in listOf(tabRow, actions)) for (i in 0 until row.childCount) styleButton(row.getChildAt(i) as TextView, false)
        updateTabs()
        list.background = ColorDrawable(c.normalBg)
    }

    private fun styleButton(v: TextView, active: Boolean) {
        val c = colors()
        v.setTextColor(if (active) c.current else c.normal)
        v.background = GradientDrawable().apply {
            setColor(if (active) c.selectedBg else blend(c.normalBg, c.normal, 0.06f))
            setStroke((1 * dp).toInt().coerceAtLeast(1), withAlpha(c.normal, 0x50))
        }
    }

    private fun updateTabs() {
        Tab.entries.forEachIndexed { i, t -> styleButton(tabRow.getChildAt(i) as TextView, t == tab) }
    }

    private fun withAlpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a shl 24)
    private fun blend(a: Int, b: Int, f: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - f) + ((b shr shift) and 0xFF) * f).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    // ---- Content ----

    private fun matches(t: Track, q: String) = q.isEmpty() ||
        t.title.contains(q, true) || t.artist.contains(q, true) || t.album.contains(q, true) ||
        t.albumArtist.contains(q, true) || t.genre.contains(q, true) || t.fileName.contains(q, true)

    fun refresh() {
        updateTabs()
        search.hint = if (tab == Tab.RADIO) "Search stations by name or genre…" else "Search title, artist, album, genre…"
        actions.visibility = if (tab == Tab.RADIO) View.GONE else View.VISIBLE
        if (tab == Tab.RADIO) return refreshRadio()
        val q = search.text.toString().trim()
        val fs = host as? FileSource
        val all = MediaLibrary.tracks
        val songs = all.filter { matches(it, q) }
        val rows = ArrayList<Row>()
        val d = drill

        if (d != null) {
            crumb.visibility = View.VISIBLE
            crumb.text = "◀  ${tab.label}  /  ${d.first}"
            shownSongs = d.second.filter { matches(it, q) }
            shownSongs.forEach { rows += songRow(it) }
        } else {
            crumb.visibility = View.GONE
            shownSongs = songs
            when (tab) {
                Tab.SONGS -> songs.forEach { rows += songRow(it) }
                Tab.ARTISTS -> groupRows(all, q) { it.effectiveAlbumArtist.ifBlank { "Unknown artist" } }.let(rows::addAll)
                Tab.GENRES -> groupRows(all, q) { it.genre.ifBlank { "Unknown genre" } }.let(rows::addAll)
                Tab.ALBUMS -> {
                    all.groupBy { it.album.ifBlank { "Unknown album" } to it.effectiveAlbumArtist }
                        .filter { (k, v) -> q.isEmpty() || k.first.contains(q, true) || k.second.contains(q, true) || v.any { matches(it, q) } }
                        .toSortedMap(compareBy(String.CASE_INSENSITIVE_ORDER, { it.first }))
                        .forEach { (k, v) ->
                            val sorted = v.sortedWith(compareBy(Track::discNumber, Track::trackNumber))
                            val year = v.maxOf { it.year }.takeIf { it > 0 }?.let { " ($it)" } ?: ""
                            rows += Row(k.first + year, k.second, "${v.size}", { drillInto(k.first, sorted) }, { groupMenu(k.first, sorted) })
                        }
                }
                Tab.RADIO -> Unit
                Tab.PLAYLISTS -> MediaLibrary.savedPlaylists(context).filter { q.isEmpty() || it.contains(q, true) }.forEach { name ->
                    rows += Row(name, "Saved playlist", "", {
                        drillInto(name, MediaLibrary.loadPlaylist(context, name))
                    }, {
                        val listTracks = MediaLibrary.loadPlaylist(context, name)
                        host.showMenu(this, width / 2f, height / 2f, listOf(
                            MenuEntry("Load (replace playlist)") { PlayerHub.replace(listTracks, play = true) },
                            MenuEntry("Enqueue") { PlayerHub.add(listTracks) },
                            MenuEntry("Delete") { MediaLibrary.deletePlaylist(context, name); refresh() },
                        ))
                    })
                }
            }
        }

        adapter.rows = rows
        adapter.notifyDataSetChanged()

        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            fs != null && !fs.hasAudioPermission() -> "FoldAmp needs permission to read your music.\n\nTap here to grant access."
            MediaLibrary.scanning -> "Scanning your music…"
            tab == Tab.PLAYLISTS && d == null -> "No saved playlists yet.\nUse LIST OPTS ▸ Save playlist in the playlist window."
            all.isEmpty() -> "No music found on this device.\nTap FILES… to open audio files directly."
            else -> "Nothing matches \"$q\"."
        }
        empty.setOnClickListener { if (fs != null && !fs.hasAudioPermission()) fs.requestAudioPermission() }
    }

    // ---- Radio ----

    private fun loadStations() {
        val q = search.text.toString().trim()
        stationsQuery = q
        stationsLoading = true
        refresh()
        RadioBrowser.stations(q) { result ->
            stationsLoading = false
            stationsError = result.isFailure
            stations = result.getOrNull()
                // Offline or the directory is down: offer the built-in channels so radio still works.
                ?: RadioBrowser.fallback.filter { q.isEmpty() || matches(it, q) }
            list.scrollToPosition(0)
            refresh()
        }
    }

    private fun refreshRadio() {
        val q = stationsQuery.orEmpty()
        val shown = stations.orEmpty()
        crumb.visibility = View.VISIBLE
        crumb.text = when {
            stationsError -> "Station directory unreachable — showing SomaFM channels"
            q.isEmpty() -> "Most popular stations · radio-browser.info"
            else -> "Stations matching \"$q\" · radio-browser.info"
        }
        adapter.rows = shown.map { stationRow(it) }
        adapter.notifyDataSetChanged()
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (stationsLoading || stations == null) "Tuning the dial…" else "No stations match \"$q\"."
        empty.setOnClickListener(null)
    }

    private fun stationRow(t: Track) = Row(
        label = t.title,
        detail = listOf(t.comment, codecLabel(t)).filter { it.isNotBlank() }.joinToString(" · "),
        right = if (MediaLibrary.isFavorite(t)) "★" else "",
        onClick = { tuneIn(t) },
        onLongClick = { toggleFavorite(t) },
    )

    private fun codecLabel(t: Track): String {
        val codec = when (t.mimeType) {
            MimeTypes.AUDIO_MPEG -> "MP3"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_OGG -> "OGG"
            MimeTypes.AUDIO_OPUS -> "OPUS"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.APPLICATION_M3U8 -> "HLS"
            else -> ""
        }
        return listOf(codec, t.bitrateKbps.takeIf { it > 0 }?.let { "${it}k" }.orEmpty()).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** Plays a station right away, reusing its playlist entry if it's already queued. */
    private fun tuneIn(t: Track) {
        val existing = PlayerHub.tracks.indexOfFirst { it.uri == t.uri }
        if (existing >= 0) PlayerHub.playAt(existing) else PlayerHub.add(listOf(t), playFirst = true)
        RadioBrowser.countClick(t)
        host.toast("Tuning in: ${t.title}")
    }

    private fun toggleFavorite(t: Track) {
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val added = MediaLibrary.toggleFavorite(context, t)
        host.toast(if (added) "★ Added to your library: ${t.title}" else "Removed from your library: ${t.title}")
    }

    private fun groupRows(all: List<Track>, q: String, key: (Track) -> String): List<Row> =
        all.groupBy(key)
            .filter { (k, v) -> q.isEmpty() || k.contains(q, true) || v.any { matches(it, q) } }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .map { (k, v) ->
                val albums = v.map { it.album }.distinct().size
                Row(k, "$albums album${if (albums == 1) "" else "s"}", "${v.size}", { drillInto(k, v) }, { groupMenu(k, v) })
            }

    private fun drillInto(title: String, songs: List<Track>) {
        drill = title to songs
        list.scrollToPosition(0)
        refresh()
    }

    private fun groupMenu(title: String, songs: List<Track>) {
        host.showMenu(this, width / 2f, height / 2f, listOf(
            MenuEntry("Play \"$title\"") { PlayerHub.replace(songs, play = true) },
            MenuEntry("Enqueue \"$title\"") { PlayerHub.add(songs); host.toast("Added ${songs.size} tracks") },
        ))
    }

    private fun songRow(t: Track) = Row(
        label = t.title,
        detail = if (t.isStream) listOf(t.album, t.comment).filter { it.isNotBlank() }.joinToString(" — ")
            else listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" — "),
        right = if (t.isStream) "★" else formatTime(t.durationMs),
        // Songs queue up, Winamp-style; a radio station is something you switch to, so it plays.
        onClick = { if (t.isStream) tuneIn(t) else { PlayerHub.add(listOf(t)); host.toast("Added: ${t.displayTitle}") } },
        onLongClick = {
            host.showMenu(this, width / 2f, height / 2f, listOf(
                MenuEntry("Play now") { PlayerHub.add(listOf(t), playFirst = true) },
                MenuEntry("Enqueue") { PlayerHub.add(listOf(t)) },
                MenuEntry("Play next") {
                    PlayerHub.add(listOf(t))
                    val from = PlayerHub.tracks.size - 1
                    val to = (PlayerHub.currentIndex + 1).coerceAtMost(from)
                    PlayerHub.move(from, to)
                },
            ) + if (t.isStream) listOf(MenuEntry("Remove from library") { toggleFavorite(t) }) else emptyList())
        },
    )

    private fun playAll(enqueue: Boolean) {
        val songs = shownSongs
        if (songs.isEmpty()) return
        if (enqueue) { PlayerHub.add(songs); host.toast("Added ${songs.size} tracks") }
        else PlayerHub.replace(songs, play = true)
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.Holder>() {
        var rows: List<Row> = emptyList()

        inner class Holder(val root: LinearLayout, val label: TextView, val detail: TextView, val right: TextView) : RecyclerView.ViewHolder(root)

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val pad = (8 * dp).toInt()
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val label = TextView(context).apply { textSize = 14f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            val detail = TextView(context).apply { textSize = 11.5f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            texts.addView(label); texts.addView(detail)
            val right = TextView(context).apply { textSize = 13f; setPadding(pad, 0, 0, 0) }
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, (6 * dp).toInt(), pad, (6 * dp).toInt())
                isClickable = true
                addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(right)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            return Holder(root, label, detail, right)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val row = rows[position]
            val c = colors()
            h.label.text = row.label
            h.detail.text = row.detail
            h.detail.visibility = if (row.detail.isEmpty()) View.GONE else View.VISIBLE
            h.right.text = row.right
            h.label.setTextColor(c.normal)
            h.detail.setTextColor(withAlpha(c.normal, 0xA0))
            h.right.setTextColor(c.normal)
            h.root.background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(c.selectedBg))
                addState(intArrayOf(), ColorDrawable(c.normalBg))
            }
            h.root.setOnClickListener { row.onClick() }
            h.root.setOnLongClickListener { row.onLongClick?.invoke(); row.onLongClick != null }
        }
    }
}
