package org.fossify.voicerecorder.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.AttributeSet
import android.widget.SeekBar
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getFormattedDuration
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.value
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.helpers.isTiramisuPlus
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.activities.SimpleActivity
import org.fossify.voicerecorder.activities.TranscriptActivity
import org.fossify.voicerecorder.adapters.RecordingsAdapter
import org.fossify.voicerecorder.adapters.RecordingsListMode
import org.fossify.voicerecorder.databinding.FragmentPlayerBinding
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.interfaces.RefreshRecordingsListener
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.receivers.BecomingNoisyReceiver
import org.fossify.voicerecorder.store.Recording
import org.fossify.voicerecorder.store.TranscriptStore
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.Stack
import java.util.Timer
import java.util.TimerTask

class PlayerFragment(
    context: Context, attributeSet: AttributeSet
) : MyViewPagerFragment(context, attributeSet), RefreshRecordingsListener {

    companion object {
        private const val FAST_FORWARD_SKIP_MS = 10000
    }

    private var player: MediaPlayer? = null
    private var progressTimer = Timer()
    private var playedRecordingIDs = Stack<Int>()
    private var itemsIgnoringSearch = ArrayList<Recording>()
    private var lastSearchQuery = ""
    private var bus: EventBus? = null
    private var prevSaveFolder: Uri? = null
    private var prevRecycleBinState = context.config.useRecycleBin
    private var playOnPreparation = true
    private var currentRecording: Recording? = null
    private var pendingSeekMs: Int = -1
    private var listMode: RecordingsListMode = RecordingsListMode.AUDIO
    private var transcriptIds: Set<Int> = emptySet()
    private lateinit var binding: FragmentPlayerBinding

    private var becomingNoisyReceiver: BecomingNoisyReceiver? = null
    private var isReceiverRegistered = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentPlayerBinding.bind(this)
    }

    override fun onResume() {
        setupColors()
        if (prevSaveFolder != null && context!!.config.saveRecordingsFolder != prevSaveFolder || context.config.useRecycleBin != prevRecycleBinState) {
            loadRecordings()
        } else {
            getRecordingsAdapter()?.updateTextColor(context.getProperTextColor())
        }

        storePrevState()
    }

    override fun onDestroy() {
        unregisterNoisyAudioReceiver()
        player?.stop()
        player?.release()
        player = null

        bus?.unregister(this)
        progressTimer.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        bus = EventBus.getDefault()
        bus!!.register(this)
        setupColors()
        loadRecordings()
        initMediaPlayer()
        setupViews()
        storePrevState()
    }

    override fun onLoadingStart() {
        if (itemsIgnoringSearch.isEmpty()) {
            binding.loadingIndicator.show()
        } else {
            binding.loadingIndicator.hide()
        }
    }

    override fun onLoadingEnd(recordings: ArrayList<Recording>) {
        binding.loadingIndicator.hide()
        itemsIgnoringSearch = recordings
        if (listMode == RecordingsListMode.TRANSCRIPTS) {
            recomputeTranscriptIds { setupAdapter(filteredForCurrentMode()) }
        } else {
            setupAdapter(filteredForCurrentMode())
        }
    }

    private fun setupViews() {
        binding.playPauseBtn.setOnClickListener {
            if (playedRecordingIDs.empty() || binding.playerProgressbar.max == 0) {
                binding.nextBtn.callOnClick()
            } else {
                togglePlayPause()
            }
        }

        binding.playerProgressCurrent.setOnClickListener {
            skip(false)
        }

        binding.playerProgressMax.setOnClickListener {
            skip(true)
        }

        binding.previousBtn.setOnClickListener {
            if (playedRecordingIDs.isEmpty()) {
                return@setOnClickListener
            }

            val adapter = getRecordingsAdapter() ?: return@setOnClickListener
            var wantedRecordingID = playedRecordingIDs.pop()
            if (wantedRecordingID == adapter.currRecordingId && !playedRecordingIDs.isEmpty()) {
                wantedRecordingID = playedRecordingIDs.pop()
            }

            val prevRecordingIndex = adapter.recordings.indexOfFirst { it.id == wantedRecordingID }
            val prevRecording = adapter.recordings.getOrNull(prevRecordingIndex) ?: return@setOnClickListener
            playRecording(prevRecording, true)
        }

        binding.playerTitle.setOnLongClickListener {
            if (binding.playerTitle.value.isNotEmpty()) {
                context.copyToClipboard(binding.playerTitle.value)
            }
            true
        }

        binding.nextBtn.setOnClickListener {
            val adapter = getRecordingsAdapter()
            if (adapter == null || adapter.recordings.isEmpty()) {
                return@setOnClickListener
            }

            val oldRecordingIndex = adapter.recordings.indexOfFirst { it.id == adapter.currRecordingId }
            val newRecordingIndex = (oldRecordingIndex + 1) % adapter.recordings.size
            val newRecording = adapter.recordings.getOrNull(newRecordingIndex) ?: return@setOnClickListener
            playRecording(newRecording, true)
            playedRecordingIDs.push(newRecording.id)
        }

        binding.transcriptBtn.setOnClickListener {
            val recording = currentRecording ?: return@setOnClickListener
            openTranscriptActivity(recording)
        }

        setupListModeSelector()
    }

    private fun openTranscriptActivity(recording: Recording) {
        pausePlayback()
        val intent = android.content.Intent(context, TranscriptActivity::class.java).apply {
            putExtra(TranscriptActivity.EXTRA_RECORDING_URI_STRING, recording.uri.toString())
        }
        context.startActivity(intent)
    }

    private fun setupListModeSelector() {
        binding.listModeAudio.setOnClickListener {
            if (listMode != RecordingsListMode.AUDIO) {
                listMode = RecordingsListMode.AUDIO
                refreshListModeSelectorStatus()
                applyListMode()
            }
        }
        binding.listModeTranscripts.setOnClickListener {
            if (listMode != RecordingsListMode.TRANSCRIPTS) {
                listMode = RecordingsListMode.TRANSCRIPTS
                refreshListModeSelectorStatus()
                applyListMode()
            }
        }
        refreshListModeSelectorStatus()
    }

    private fun refreshListModeSelectorStatus() {
        val properTextColor = context.getProperTextColor()
        val properPrimaryColor = context.getProperPrimaryColor()
        val contrastColor = properPrimaryColor.getContrastColor()
        val audioSelected = listMode == RecordingsListMode.AUDIO
        if (audioSelected) {
            binding.listModeAudio.setBackgroundResource(R.drawable.tab_selector_selected)
            binding.listModeAudio.background.applyColorFilter(properPrimaryColor)
            binding.listModeAudio.setTextColor(contrastColor)
            binding.listModeTranscripts.setBackgroundResource(android.R.color.transparent)
            binding.listModeTranscripts.setTextColor(properTextColor)
        } else {
            binding.listModeAudio.setBackgroundResource(android.R.color.transparent)
            binding.listModeAudio.setTextColor(properTextColor)
            binding.listModeTranscripts.setBackgroundResource(R.drawable.tab_selector_selected)
            binding.listModeTranscripts.background.applyColorFilter(properPrimaryColor)
            binding.listModeTranscripts.setTextColor(contrastColor)
        }
    }

    private fun applyListMode() {
        getRecordingsAdapter()?.let { adapter ->
            adapter.mode = listMode
            adapter.finishActMode()
        }
        if (listMode == RecordingsListMode.TRANSCRIPTS) {
            recomputeTranscriptIds {
                setupAdapter(filteredForCurrentMode())
            }
        } else {
            setupAdapter(filteredForCurrentMode())
        }
    }

    private fun filteredForCurrentMode(): ArrayList<Recording> {
        val base = if (lastSearchQuery.isEmpty()) {
            itemsIgnoringSearch
        } else {
            itemsIgnoringSearch.filter { it.title.contains(lastSearchQuery, true) }
        }
        return when (listMode) {
            RecordingsListMode.AUDIO -> ArrayList(base)
            RecordingsListMode.TRANSCRIPTS -> ArrayList(base.filter { it.id in transcriptIds })
        }
    }

    private fun recomputeTranscriptIds(then: () -> Unit) {
        ensureBackgroundThread {
            val store = TranscriptStore(context, context.config.saveRecordingsFolder)
            val ids = itemsIgnoringSearch.filter { store.hasTranscript(it) }.map { it.id }.toSet()
            (context as? SimpleActivity)?.runOnUiThread {
                transcriptIds = ids
                then()
            }
        }
    }

    /**
     * Seek the currently-playing recording to [positionMs] and start playback.
     * If the player hasn't finished preparing yet, the seek is queued and applied
     * once `onPrepared` fires.
     */
    fun seekToAndPlay(positionMs: Long) {
        val target = positionMs.toInt()
        val mediaPlayer = player ?: return
        try {
            mediaPlayer.seekTo(target)
            resumePlayback()
            binding.playerProgressbar.progress = target / 1000
        } catch (_: IllegalStateException) {
            pendingSeekMs = target
            playOnPreparation = true
        }
    }

    override fun refreshRecordings() = loadRecordings()

    private fun setupAdapter(recordings: ArrayList<Recording>) {
        binding.recordingsFastscroller.beVisibleIf(recordings.isNotEmpty())
        binding.recordingsPlaceholder.beVisibleIf(recordings.isEmpty())
        if (recordings.isEmpty()) {
            val stringId = when {
                lastSearchQuery.isNotEmpty() -> org.fossify.commons.R.string.no_items_found
                listMode == RecordingsListMode.TRANSCRIPTS -> R.string.no_transcripts_found
                isQPlus() -> R.string.no_recordings_found
                else -> R.string.no_recordings_in_folder_found
            }

            binding.recordingsPlaceholder.text = context.getString(stringId)
            if (listMode == RecordingsListMode.AUDIO) {
                resetProgress(null)
                player?.stop()
            }
        }

        val adapter = getRecordingsAdapter()
        if (adapter == null) {
            RecordingsAdapter(
                activity = context as SimpleActivity,
                recordings = recordings,
                refreshListener = this,
                recyclerView = binding.recordingsList,
                mode = listMode,
            ) {
                onRecordingTapped(it as Recording)
            }.apply {
                binding.recordingsList.adapter = this
            }

            if (context.areSystemAnimationsEnabled) {
                binding.recordingsList.scheduleLayoutAnimation()
            }
        } else {
            adapter.mode = listMode
            adapter.updateItems(recordings)
        }
    }

    private fun onRecordingTapped(recording: Recording) {
        if (listMode == RecordingsListMode.TRANSCRIPTS) {
            openTranscriptActivity(recording)
            return
        }
        playRecording(recording, true)
        if (playedRecordingIDs.isEmpty() || playedRecordingIDs.peek() != recording.id) {
            playedRecordingIDs.push(recording.id)
        }
    }

    private fun initMediaPlayer() {
        player = MediaPlayer().apply {
            setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )

            setOnCompletionListener {
                progressTimer.cancel()
                unregisterNoisyAudioReceiver()
                binding.playerProgressbar.progress = binding.playerProgressbar.max
                binding.playerProgressCurrent.text = binding.playerProgressMax.text
                binding.playPauseBtn.setImageDrawable(getToggleButtonIcon(false))
            }

            setOnPreparedListener {
                if (pendingSeekMs >= 0) {
                    try {
                        seekTo(pendingSeekMs)
                        binding.playerProgressbar.progress = pendingSeekMs / 1000
                    } catch (_: IllegalStateException) {
                    }
                    pendingSeekMs = -1
                }
                if (playOnPreparation) {
                    resumePlayback()
                }

                playOnPreparation = true
            }
        }
    }

    override fun playRecording(recording: Recording, playOnPrepared: Boolean) {
        resetProgress(recording)
        currentRecording = recording
        binding.transcriptBtn.visibility = android.view.View.VISIBLE
        (binding.recordingsList.adapter as RecordingsAdapter).updateCurrentRecording(recording.id)
        playOnPreparation = playOnPrepared

        player!!.apply {
            reset()

            try {
                setDataSource(context, recording.uri)
            } catch (e: Exception) {
                context?.showErrorToast(e)
                return
            }

            try {
                prepareAsync()
            } catch (e: Exception) {
                context.showErrorToast(e)
                return
            }
        }

        binding.playPauseBtn.setImageDrawable(getToggleButtonIcon(playOnPreparation))
        binding.playerProgressbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && !playedRecordingIDs.isEmpty()) {
                    player?.seekTo(progress * 1000)
                    binding.playerProgressCurrent.text = progress.getFormattedDuration()
                    resumePlayback()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    @SuppressLint("DiscouragedApi")
    private fun setupProgressTimer() {
        progressTimer.cancel()
        progressTimer = Timer()
        progressTimer.scheduleAtFixedRate(getProgressUpdateTask(), 1000, 1000)
    }

    private fun getProgressUpdateTask() = object : TimerTask() {
        override fun run() {
            Handler(Looper.getMainLooper()).post {
                if (player != null) {
                    val progress = Math.round(player!!.currentPosition / 1000.toDouble()).toInt()
                    updateCurrentProgress(progress)
                    binding.playerProgressbar.progress = progress
                }
            }
        }
    }

    private fun updateCurrentProgress(seconds: Int) {
        binding.playerProgressCurrent.text = seconds.getFormattedDuration()
    }

    private fun resetProgress(recording: Recording?) {
        updateCurrentProgress(0)
        binding.playerProgressbar.progress = 0
        binding.playerProgressbar.max = recording?.duration ?: 0
        binding.playerTitle.text = recording?.title ?: ""
        binding.playerProgressMax.text = (recording?.duration ?: 0).getFormattedDuration()
    }

    fun onSearchTextChanged(text: String) {
        lastSearchQuery = text
        setupAdapter(filteredForCurrentMode())
    }

    private fun togglePlayPause() {
        if (getIsPlaying()) {
            pausePlayback()
        } else {
            resumePlayback()
        }
    }

    private fun pausePlayback() {
        unregisterNoisyAudioReceiver()
        player?.pause()
        binding.playPauseBtn.setImageDrawable(getToggleButtonIcon(false))
        progressTimer.cancel()
    }

    private fun resumePlayback() {
        registerNoisyAudioReceiver()
        player?.start()
        binding.playPauseBtn.setImageDrawable(getToggleButtonIcon(true))
        setupProgressTimer()
    }

    private fun getToggleButtonIcon(isPlaying: Boolean): Drawable {
        val drawable = if (isPlaying) {
            org.fossify.commons.R.drawable.ic_pause_vector
        } else {
            org.fossify.commons.R.drawable.ic_play_vector
        }

        return resources.getColoredDrawableWithColor(
            drawableId = drawable, color = context.getProperPrimaryColor().getContrastColor()
        )
    }

    private fun skip(forward: Boolean) {
        if (playedRecordingIDs.empty()) {
            return
        }

        val curr = player?.currentPosition ?: return
        var newProgress = if (forward) curr + FAST_FORWARD_SKIP_MS else curr - FAST_FORWARD_SKIP_MS
        if (newProgress > player!!.duration) {
            newProgress = player!!.duration
        }

        player!!.seekTo(newProgress)
        resumePlayback()
    }

    private fun getIsPlaying() = player?.isPlaying == true

    private fun getRecordingsAdapter() = binding.recordingsList.adapter as? RecordingsAdapter

    private fun storePrevState() {
        prevSaveFolder = context!!.config.saveRecordingsFolder
        prevRecycleBinState = context.config.useRecycleBin
    }

    private fun setupColors() {
        val properPrimaryColor = context.getProperPrimaryColor()
        binding.recordingsFastscroller.updateColors(properPrimaryColor)
        context.updateTextColors(binding.playerHolder)

        val textColor = context.getProperTextColor()
        arrayListOf(binding.previousBtn, binding.nextBtn).forEach {
            it.applyColorFilter(textColor)
        }

        binding.playPauseBtn.background.applyColorFilter(properPrimaryColor)
        binding.playPauseBtn.setImageDrawable(getToggleButtonIcon(getIsPlaying()))

        binding.loadingIndicator.setIndicatorColor(properPrimaryColor)
        refreshListModeSelectorStatus()
    }

    fun finishActMode() = getRecordingsAdapter()?.finishActMode()

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun recordingCompleted(@Suppress("UNUSED_PARAMETER") event: Events.RecordingCompleted) {
        refreshRecordings()
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun recordingMovedToRecycleBin(@Suppress("UNUSED_PARAMETER") event: Events.RecordingTrashUpdated) {
        refreshRecordings()
    }

    private fun registerNoisyAudioReceiver() {
        if (isReceiverRegistered) return
        if (becomingNoisyReceiver == null) {
            becomingNoisyReceiver = BecomingNoisyReceiver(onBecomingNoisy = ::pausePlayback)
        }

        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (isTiramisuPlus()) {
            context.registerReceiver(becomingNoisyReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(becomingNoisyReceiver, filter)
        }

        isReceiverRegistered = true
    }

    private fun unregisterNoisyAudioReceiver() {
        if (!isReceiverRegistered || becomingNoisyReceiver == null) return
        try {
            isReceiverRegistered = false
            context.unregisterReceiver(becomingNoisyReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }
}
