package org.fossify.voicerecorder.activities

import android.content.Intent
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.MenuItem
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.widget.SearchView
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getFormattedDuration
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.databinding.ActivityTranscriptBinding
import org.fossify.voicerecorder.databinding.ItemTranscriptSegmentBinding
import org.fossify.voicerecorder.extensions.buildShareTranscriptJsonIntent
import org.fossify.voicerecorder.extensions.buildShareTranscriptTextIntent
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.recordingStore
import org.fossify.voicerecorder.extensions.toShareableText
import org.fossify.voicerecorder.helpers.ACTION_CANCEL_TRANSCRIPTION
import org.fossify.voicerecorder.helpers.ACTION_START_TRANSCRIPTION
import org.fossify.voicerecorder.helpers.EXTRA_LANGUAGE
import org.fossify.voicerecorder.helpers.EXTRA_MODEL_ID
import org.fossify.voicerecorder.helpers.EXTRA_RECORDING_URI
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.models.TranscriptionPhase
import org.fossify.voicerecorder.services.TranscriptionService
import org.fossify.voicerecorder.store.Recording
import org.fossify.voicerecorder.store.Transcript
import org.fossify.voicerecorder.store.TranscriptStore
import org.fossify.voicerecorder.transcribe.model.ModelCatalog
import org.fossify.voicerecorder.transcribe.model.ModelManager
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

/**
 * Full-screen transcript viewer with toolbar (back, search, overflow), in-page search highlighting,
 * tap-to-seek segments, and an embedded mini player. Replaces the previous TranscriptDialog.
 */
@Suppress("TooManyFunctions", "LargeClass")
class TranscriptActivity : SimpleActivity() {

    companion object {
        const val EXTRA_RECORDING_URI_STRING = "org.fossify.voicerecorder.extra.RECORDING_URI"
        private const val PCT_MAX = 100
        private const val MS_PER_SECOND = 1000L
        private const val SEC_PER_MIN = 60L
        private const val BYTES_PER_MB = 1_000_000L
        private const val PASSIVE_HIGHLIGHT_ALPHA = 0x55000000.toInt()
        private const val PLAYHEAD_TINT_ALPHA = 0x33000000.toInt()
        private const val RGB_MASK = 0x00FFFFFF
        private const val PLAYHEAD_TICK_MS = 200L
    }

    private lateinit var binding: ActivityTranscriptBinding
    private var recording: Recording? = null
    private var currentTranscript: Transcript? = null
    private val segmentBindings = mutableListOf<ItemTranscriptSegmentBinding>()

    private var player: MediaPlayer? = null
    private var progressTimer = Timer()
    private var pendingSeekMs: Int = -1
    private var playOnPreparation = false

    private var searchQuery: String = ""
    private val matches = mutableListOf<Match>()
    private var currentMatchIndex = 0
    private var playheadSegmentIndex: Int = -1

    private data class Match(val segmentIndex: Int, val start: Int, val end: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTranscriptBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomSystem = listOf(binding.transcriptContent))

        EventBus.getDefault().register(this)
        setupToolbarMenu()
        initMediaPlayer()
        wireControls()
        loadRecordingAndTranscript()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.transcriptAppbar, NavigationIcon.Arrow)
        applyColors()
        updateTextColors(binding.transcriptContent)
    }

    override fun onDestroy() {
        super.onDestroy()
        EventBus.getDefault().unregister(this)
        progressTimer.cancel()
        player?.release()
        player = null
    }

    private fun setupToolbarMenu() {
        val toolbar = binding.transcriptToolbar
        toolbar.inflateMenu(R.menu.menu_transcript)
        tintToolbarMenuIcons()

        val searchItem = toolbar.menu.findItem(R.id.transcript_menu_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = getString(R.string.transcript_search_hint)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                applySearchQuery(newText.orEmpty())
                return true
            }
        })
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem) = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                applySearchQuery("")
                return true
            }
        })
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.transcript_menu_share_text -> { shareTranscriptAsText(); true }
                R.id.transcript_menu_share_json -> { shareTranscriptAsJson(); true }
                R.id.transcript_menu_copy -> { copyTranscriptText(); true }
                R.id.transcript_menu_re_transcribe -> { startTranscription(); true }
                R.id.transcript_menu_delete -> { confirmDeleteTranscript(); true }
                else -> false
            }
        }
    }

    private fun tintToolbarMenuIcons() {
        val contrast = getProperPrimaryColor().getContrastColor()
        val menu = binding.transcriptToolbar.menu
        for (i in 0 until menu.size()) {
            menu.getItem(i).icon?.setTint(contrast)
        }
        binding.transcriptToolbar.overflowIcon?.setTint(contrast)
    }

    // ---- setup ----

    private fun applyColors() {
        val primary = getProperPrimaryColor()
        val contrast = primary.getContrastColor()
        binding.transcriptToolbar.setTitleTextColor(contrast)
        binding.transcriptToolbar.navigationIcon?.setTint(contrast)
        binding.transcriptToolbar.overflowIcon?.setTint(contrast)
        binding.transcriptPlayPauseBtn.applyColorFilter(getProperTextColor())
    }

    private fun wireControls() {
        binding.transcriptStartBtn.setOnClickListener { startTranscription() }
        binding.transcriptCancelBtn.setOnClickListener { cancelTranscription() }
        binding.transcriptSearchPrev.setOnClickListener { goToMatch(currentMatchIndex - 1) }
        binding.transcriptSearchNext.setOnClickListener { goToMatch(currentMatchIndex + 1) }

        binding.transcriptPlayPauseBtn.setOnClickListener { togglePlayPause() }
        binding.transcriptPlayerProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    player?.seekTo(progress * MS_PER_SECOND.toInt())
                    binding.transcriptPlayerCurrent.text = progress.getFormattedDuration()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private fun loadRecordingAndTranscript() {
        val uriStr = intent.getStringExtra(EXTRA_RECORDING_URI_STRING)
        if (uriStr == null) {
            toast(R.string.recording_store_error_message)
            finish()
            return
        }
        val uri = Uri.parse(uriStr)
        ensureBackgroundThread {
            val rec = recordingStore.all().firstOrNull { it.uri == uri }
                ?: recordingStore.all(trashed = true).firstOrNull { it.uri == uri }
            if (rec == null) {
                runOnUiThread {
                    toast(R.string.recording_store_error_message)
                    finish()
                }
                return@ensureBackgroundThread
            }
            val transcript = TranscriptStore(this, config.saveRecordingsFolder).read(rec)
            runOnUiThread {
                recording = rec
                currentTranscript = transcript
                binding.transcriptToolbar.title = rec.title
                prepareMediaSource(rec.uri)
                renderState(transcript)
            }
        }
    }

    // ---- state rendering ----

    private fun renderState(transcript: Transcript?) {
        when {
            TranscriptionService.isRunning -> renderBusy(getString(R.string.transcribing), 0, indeterminate = true)
            transcript != null -> renderReady(transcript)
            else -> renderIdle()
        }
    }

    private fun renderIdle() {
        binding.transcriptIdle.visibility = View.VISIBLE
        binding.transcriptBusy.visibility = View.GONE
        binding.transcriptReady.visibility = View.GONE

        val spec = ModelCatalog.byId(config.transcribeModelId ?: ModelCatalog.DEFAULT.id) ?: ModelCatalog.DEFAULT
        val mgr = ModelManager(this)
        binding.transcriptIdleSubtitle.text = if (mgr.isModelInstalled(spec)) {
            spec.displayName
        } else {
            "${spec.displayName} (~${spec.archiveSizeBytes / BYTES_PER_MB} MB will be downloaded)"
        }
    }

    private fun renderBusy(label: String, progress: Int, indeterminate: Boolean) {
        binding.transcriptIdle.visibility = View.GONE
        binding.transcriptBusy.visibility = View.VISIBLE
        binding.transcriptReady.visibility = View.GONE
        binding.transcriptBusyLabel.text = label
        binding.transcriptBusyProgress.isIndeterminate = indeterminate
        if (!indeterminate) {
            binding.transcriptBusyProgress.setProgressCompat(progress, true)
        }
    }

    private fun renderReady(transcript: Transcript) {
        binding.transcriptIdle.visibility = View.GONE
        binding.transcriptBusy.visibility = View.GONE
        binding.transcriptReady.visibility = View.VISIBLE

        val langLabel = transcript.language.ifBlank { "?" }
        val processingMs = transcript.processingMs
        val processedSuffix = if (processingMs != null && processingMs > 0L) {
            " · ${getString(R.string.transcript_processing_time, formatProcessingTime(processingMs))}"
        } else {
            ""
        }
        binding.transcriptReadySubtitle.text =
            "Language: $langLabel · ${transcript.segments.size} segments$processedSuffix"

        val container = binding.transcriptSegmentsContainer
        container.removeAllViews()
        segmentBindings.clear()
        playheadSegmentIndex = -1

        val inflater = layoutInflater
        for (segment in transcript.segments) {
            val itemBinding = ItemTranscriptSegmentBinding.inflate(inflater, container, false)
            itemBinding.segmentTimestamp.text = formatTimestamp(segment.startMs)
            itemBinding.segmentText.text = segment.text
            itemBinding.root.setOnClickListener { seekToAndPlay(segment.startMs) }
            itemBinding.root.setOnLongClickListener {
                copyToClipboard(segment.text)
                true
            }
            container.addView(itemBinding.root)
            segmentBindings.add(itemBinding)
        }

        applySearchQuery(searchQuery)
    }

    // ---- search ----

    private fun applySearchQuery(query: String) {
        searchQuery = query
        val transcript = currentTranscript ?: run {
            binding.transcriptSearchBar.visibility = View.GONE
            return
        }
        matches.clear()
        if (query.isNotEmpty()) {
            collectMatches(transcript, query)
        }
        currentMatchIndex = 0
        renderHighlights()
        renderSearchBar()
        if (matches.isNotEmpty()) scrollToMatch(currentMatchIndex)
    }

    private fun collectMatches(transcript: Transcript, query: String) {
        val needle = query.lowercase(Locale.ROOT)
        transcript.segments.forEachIndexed { idx, segment ->
            val haystack = segment.text.lowercase(Locale.ROOT)
            findOccurrences(haystack, needle).forEach { pos ->
                matches.add(Match(idx, pos, pos + query.length))
            }
        }
    }

    private fun findOccurrences(haystack: String, needle: String): List<Int> {
        val out = mutableListOf<Int>()
        var from = 0
        while (from <= haystack.length) {
            val pos = haystack.indexOf(needle, from)
            if (pos < 0) return out
            out.add(pos)
            from = pos + 1
        }
        return out
    }

    private fun renderSearchBar() {
        if (searchQuery.isEmpty()) {
            binding.transcriptSearchBar.visibility = View.GONE
            return
        }
        binding.transcriptSearchBar.visibility = View.VISIBLE
        binding.transcriptSearchCount.text = if (matches.isEmpty()) {
            getString(R.string.transcript_search_no_match)
        } else {
            getString(R.string.transcript_search_count, currentMatchIndex + 1, matches.size)
        }
    }

    private fun renderHighlights() {
        val transcript = currentTranscript ?: return
        val highlightColor = getProperPrimaryColor()
        val activeBg = highlightColor
        val activeFg = highlightColor.getContrastColor()
        val passiveBg = (highlightColor and RGB_MASK) or PASSIVE_HIGHLIGHT_ALPHA

        transcript.segments.forEachIndexed { segmentIdx, segment ->
            val itemBinding = segmentBindings.getOrNull(segmentIdx)
            if (itemBinding != null) {
                itemBinding.segmentText.text = highlightSegment(
                    segment.text, segmentIdx, activeBg, passiveBg, activeFg
                )
            }
        }
    }

    private fun highlightSegment(
        text: String,
        segmentIdx: Int,
        activeBg: Int,
        passiveBg: Int,
        activeFg: Int,
    ): CharSequence {
        if (searchQuery.isEmpty()) return text
        val span = SpannableString(text)
        val segmentMatches = matches.withIndex().filter { it.value.segmentIndex == segmentIdx }
        for ((matchIdx, match) in segmentMatches) {
            val isActive = matchIdx == currentMatchIndex
            val bg = if (isActive) activeBg else passiveBg
            span.setSpan(BackgroundColorSpan(bg), match.start, match.end, 0)
            if (isActive) {
                span.setSpan(ForegroundColorSpan(activeFg), match.start, match.end, 0)
            }
        }
        return span
    }

    private fun goToMatch(index: Int) {
        if (matches.isEmpty()) return
        val total = matches.size
        currentMatchIndex = ((index % total) + total) % total
        renderHighlights()
        renderSearchBar()
        scrollToMatch(currentMatchIndex)
    }

    private fun scrollToMatch(index: Int) {
        val match = matches.getOrNull(index) ?: return
        val itemBinding = segmentBindings.getOrNull(match.segmentIndex) ?: return
        val targetTop = itemBinding.root.top
        binding.transcriptSegmentsScroller.post {
            binding.transcriptSegmentsScroller.smoothScrollTo(0, targetTop)
        }
    }

    // ---- player ----

    private fun initMediaPlayer() {
        player = MediaPlayer().apply {
            setWakeMode(this@TranscriptActivity, PowerManager.PARTIAL_WAKE_LOCK)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setOnPreparedListener {
                binding.transcriptPlayerProgress.max = duration / 1000
                binding.transcriptPlayerMax.text = (duration / 1000).getFormattedDuration()
                if (pendingSeekMs >= 0) {
                    try {
                        seekTo(pendingSeekMs)
                        binding.transcriptPlayerProgress.progress = pendingSeekMs / 1000
                    } catch (_: IllegalStateException) {
                    }
                    pendingSeekMs = -1
                }
                if (playOnPreparation) {
                    start()
                    setupProgressTimer()
                    binding.transcriptPlayPauseBtn.setImageDrawable(playPauseIcon(true))
                }
            }
            setOnCompletionListener {
                progressTimer.cancel()
                binding.transcriptPlayerProgress.progress = binding.transcriptPlayerProgress.max
                binding.transcriptPlayPauseBtn.setImageDrawable(playPauseIcon(false))
            }
        }
    }

    private fun prepareMediaSource(uri: Uri) {
        val mp = player ?: return
        try {
            mp.reset()
            mp.setDataSource(this, uri)
            mp.prepareAsync()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // setDataSource and prepareAsync each declare multiple checked exceptions
            // (IOException, IllegalStateException, IllegalArgumentException, SecurityException);
            // surface any of them via a single user-facing toast.
            showErrorToast(e)
        }
    }

    private fun seekToAndPlay(positionMs: Long) {
        val mp = player ?: return
        val target = positionMs.toInt()
        playOnPreparation = true
        try {
            mp.seekTo(target)
            if (!mp.isPlaying) mp.start()
            setupProgressTimer()
            binding.transcriptPlayerProgress.progress = target / 1000
            binding.transcriptPlayerCurrent.text = (target / 1000).getFormattedDuration()
            binding.transcriptPlayPauseBtn.setImageDrawable(playPauseIcon(true))
        } catch (_: IllegalStateException) {
            pendingSeekMs = target
        }
    }

    private fun togglePlayPause() {
        val mp = player ?: return
        try {
            if (mp.isPlaying) {
                mp.pause()
                progressTimer.cancel()
                binding.transcriptPlayPauseBtn.setImageDrawable(playPauseIcon(false))
            } else {
                mp.start()
                setupProgressTimer()
                binding.transcriptPlayPauseBtn.setImageDrawable(playPauseIcon(true))
            }
        } catch (_: IllegalStateException) {
        }
    }

    private fun setupProgressTimer() {
        progressTimer.cancel()
        progressTimer = Timer()
        progressTimer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                Handler(Looper.getMainLooper()).post {
                    val mp = player ?: return@post
                    val ms = mp.currentPosition
                    val seconds = ms / MS_PER_SECOND.toInt()
                    binding.transcriptPlayerProgress.progress = seconds
                    binding.transcriptPlayerCurrent.text = seconds.getFormattedDuration()
                    updatePlayheadSegment(ms.toLong())
                }
            }
        }, PLAYHEAD_TICK_MS, PLAYHEAD_TICK_MS)
    }

    private fun updatePlayheadSegment(positionMs: Long) {
        val segments = currentTranscript?.segments ?: return
        val newIndex = segments.indexOfFirst { positionMs in it.startMs until it.endMs }
        if (newIndex == playheadSegmentIndex) return

        // Restore previous row's default look.
        segmentBindings.getOrNull(playheadSegmentIndex)?.let { restorePlayheadStyle(it) }

        playheadSegmentIndex = newIndex
        val itemBinding = segmentBindings.getOrNull(newIndex) ?: return
        applyPlayheadStyle(itemBinding)
        autoScrollToCurrentSegment(itemBinding)
    }

    private fun applyPlayheadStyle(itemBinding: ItemTranscriptSegmentBinding) {
        val primary = getProperPrimaryColor()
        val tint = (primary and RGB_MASK) or PLAYHEAD_TINT_ALPHA
        itemBinding.root.setBackgroundColor(tint)
        itemBinding.segmentTimestamp.setTextColor(primary)
        itemBinding.segmentTimestamp.setTypeface(null, Typeface.BOLD)
    }

    private fun restorePlayheadStyle(itemBinding: ItemTranscriptSegmentBinding) {
        itemBinding.root.setBackgroundColor(Color.TRANSPARENT)
        itemBinding.segmentTimestamp.setTextColor(getProperTextColor())
        itemBinding.segmentTimestamp.setTypeface(null, Typeface.NORMAL)
    }

    private fun autoScrollToCurrentSegment(itemBinding: ItemTranscriptSegmentBinding) {
        val scroller = binding.transcriptSegmentsScroller
        val rowTop = itemBinding.root.top
        val rowBottom = rowTop + itemBinding.root.height
        val visibleTop = scroller.scrollY
        val visibleBottom = visibleTop + scroller.height
        val isOffScreen = rowBottom > visibleBottom || rowTop < visibleTop
        if (isOffScreen) {
            scroller.post { scroller.smoothScrollTo(0, rowTop) }
        }
    }

    private fun playPauseIcon(isPlaying: Boolean): Drawable {
        val resId = if (isPlaying) {
            org.fossify.commons.R.drawable.ic_pause_vector
        } else {
            org.fossify.commons.R.drawable.ic_play_vector
        }
        return resources.getColoredDrawableWithColor(drawableId = resId, color = getProperTextColor())
    }

    // ---- transcription actions ----

    private fun startTranscription() {
        val rec = recording ?: return
        if (TranscriptionService.isRunning) {
            toast(R.string.transcribing)
            return
        }
        val intent = Intent(this, TranscriptionService::class.java).apply {
            action = ACTION_START_TRANSCRIPTION
            putExtra(EXTRA_RECORDING_URI, rec.uri.toString())
            putExtra(EXTRA_MODEL_ID, config.transcribeModelId ?: ModelCatalog.DEFAULT.id)
            putExtra(EXTRA_LANGUAGE, config.transcribeLanguage)
        }
        startForegroundService(intent)
        renderBusy(getString(R.string.transcribing), 0, indeterminate = true)
    }

    private fun cancelTranscription() {
        val intent = Intent(this, TranscriptionService::class.java).apply {
            action = ACTION_CANCEL_TRANSCRIPTION
        }
        startService(intent)
    }

    private fun shareTranscriptAsText() {
        val rec = recording ?: return
        val transcript = currentTranscript ?: run { toast(R.string.transcript_failed); return }
        startActivity(buildShareTranscriptTextIntent(transcript.toShareableText(rec), rec.title))
    }

    private fun shareTranscriptAsJson() {
        val rec = recording ?: return
        ensureBackgroundThread {
            val uri = TranscriptStore(this, config.saveRecordingsFolder).sidecarUri(rec)
            runOnUiThread {
                if (uri == null) { toast(R.string.transcript_failed); return@runOnUiThread }
                startActivity(buildShareTranscriptJsonIntent(uri, rec.title))
            }
        }
    }

    private fun copyTranscriptText() {
        val rec = recording ?: return
        val transcript = currentTranscript ?: run { toast(R.string.transcript_failed); return }
        copyToClipboard(transcript.toShareableText(rec))
        toast(R.string.transcript_copied)
    }

    private fun confirmDeleteTranscript() {
        val rec = recording ?: return
        ConfirmationDialog(
            activity = this,
            message = getString(R.string.delete_transcript) + "?",
            positive = org.fossify.commons.R.string.yes,
            negative = org.fossify.commons.R.string.no,
        ) {
            ensureBackgroundThread {
                TranscriptStore(this, config.saveRecordingsFolder).delete(rec)
                runOnUiThread {
                    currentTranscript = null
                    renderIdle()
                }
            }
        }
    }

    // ---- formatting ----

    private fun formatTimestamp(ms: Long): String {
        val totalSec = ms / MS_PER_SECOND
        val mm = totalSec / SEC_PER_MIN
        val ss = totalSec % SEC_PER_MIN
        return String.format(Locale.ROOT, "%02d:%02d", mm, ss)
    }

    private fun formatProcessingTime(ms: Long): String {
        val totalSec = ms / MS_PER_SECOND
        return if (totalSec >= SEC_PER_MIN) {
            val minutes = totalSec / SEC_PER_MIN
            val seconds = totalSec % SEC_PER_MIN
            getString(R.string.transcript_processing_time_minutes, minutes, seconds)
        } else {
            getString(R.string.transcript_processing_time_seconds, ms / MS_PER_SECOND.toFloat())
        }
    }

    private fun isOurs(uri: Uri): Boolean = recording?.uri == uri

    // ---- event subscriptions ----

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onTranscriptionStarted(e: Events.TranscriptionStarted) {
        if (!isOurs(e.recordingUri)) return
        renderBusy(getString(R.string.transcribing), 0, indeterminate = true)
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onTranscriptionProgress(e: Events.TranscriptionProgress) {
        if (!isOurs(e.recordingUri)) return
        val labelRes = when (e.phase) {
            TranscriptionPhase.DOWNLOADING_MODEL -> R.string.downloading_model
            TranscriptionPhase.DECODING -> R.string.decoding_audio
            TranscriptionPhase.TRANSCRIBING -> R.string.transcribing
            TranscriptionPhase.WRITING -> R.string.transcribing
        }
        val pct = (e.fraction * PCT_MAX).toInt().coerceIn(0, PCT_MAX)
        renderBusy("${getString(labelRes)} $pct%", pct, indeterminate = false)
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onTranscriptionCompleted(e: Events.TranscriptionCompleted) {
        if (!isOurs(e.recordingUri)) return
        val rec = recording ?: return
        ensureBackgroundThread {
            val transcript = TranscriptStore(this, config.saveRecordingsFolder).read(rec)
            runOnUiThread {
                currentTranscript = transcript
                if (transcript != null) renderReady(transcript) else renderIdle()
            }
        }
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onTranscriptionFailed(e: Events.TranscriptionFailed) {
        if (!isOurs(e.recordingUri)) return
        toast(getString(R.string.transcript_failed, e.cause.message ?: "?"))
        renderState(currentTranscript)
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onTranscriptionCancelled(e: Events.TranscriptionCancelled) {
        if (!isOurs(e.recordingUri)) return
        toast(R.string.transcript_cancelled)
        renderState(currentTranscript)
    }
}
