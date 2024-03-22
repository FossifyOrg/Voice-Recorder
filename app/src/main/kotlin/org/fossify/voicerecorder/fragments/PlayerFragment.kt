package org.fossify.voicerecorder.fragments

import android.content.Context
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import android.util.AttributeSet
import android.widget.SeekBar
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.isQPlus
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.activities.SimpleActivity
import org.fossify.voicerecorder.adapters.RecordingsAdapter
import org.fossify.voicerecorder.databinding.FragmentPlayerBinding
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.getAllRecordings
import org.fossify.voicerecorder.helpers.getAudioFileContentUri
import org.fossify.voicerecorder.interfaces.RefreshRecordingsListener
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.models.Recording
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.Stack
import java.util.Timer
import java.util.TimerTask

class PlayerFragment(context: Context, attributeSet: AttributeSet) : MyViewPagerFragment(context, attributeSet), RefreshRecordingsListener {
    private val FAST_FORWARD_SKIP_MS = 10000

    private var player: MediaPlayer? = null
    private var progressTimer = Timer()
    private var playedRecordingIDs = Stack<Int>()
    private var itemsIgnoringSearch = ArrayList<Recording>()
    private var lastSearchQuery = ""
    private var bus: EventBus? = null
    private var prevSavePath = ""
    private var prevRecycleBinState = context.config.useRecycleBin
    private var playOnPreparation = true
    private lateinit var binding: FragmentPlayerBinding

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentPlayerBinding.bind(this)
    }

    override fun onResume() {
        setupColors()
        if (prevSavePath.isNotEmpty() && context!!.config.saveRecordingsFolder != prevSavePath || context.config.useRecycleBin != prevRecycleBinState) {
            itemsIgnoringSearch = getRecordings()
            setupAdapter(itemsIgnoringSearch)
        } else {
            getRecordingsAdapter()?.updateTextColor(context.getProperTextColor())
        }

        storePrevState()
    }

    override fun onDestroy() {
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
        itemsIgnoringSearch = getRecordings()
        setupAdapter(itemsIgnoringSearch)
        initMediaPlayer()
        setupViews()
        storePrevState()
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
    }

    override fun refreshRecordings() {
        itemsIgnoringSearch = getRecordings()
        setupAdapter(itemsIgnoringSearch)
    }

    private fun setupAdapter(recordings: ArrayList<Recording>) {
        binding.recordingsFastscroller.beVisibleIf(recordings.isNotEmpty())
        binding.recordingsPlaceholder.beVisibleIf(recordings.isEmpty())
        if (recordings.isEmpty()) {
            val stringId = if (lastSearchQuery.isEmpty()) {
                if (isQPlus()) {
                    R.string.no_recordings_found
                } else {
                    R.string.no_recordings_in_folder_found
                }
            } else {
                org.fossify.commons.R.string.no_items_found
            }

            binding.recordingsPlaceholder.text = context.getString(stringId)
            resetProgress(null)
            player?.stop()
        }

        val adapter = getRecordingsAdapter()
        if (adapter == null) {
            RecordingsAdapter(context as SimpleActivity, recordings, this, binding.recordingsList) {
                playRecording(it as Recording, true)
                if (playedRecordingIDs.isEmpty() || playedRecordingIDs.peek() != it.id) {
                    playedRecordingIDs.push(it.id)
                }
            }.apply {
                binding.recordingsList.adapter = this
            }

            if (context.areSystemAnimationsEnabled) {
                binding.recordingsList.scheduleLayoutAnimation()
            }
        } else {
            adapter.updateItems(recordings)
        }
    }

    private fun getRecordings(): ArrayList<Recording> {
        return context.getAllRecordings().apply {
            sortByDescending { it.timestamp }
        }
    }

    private fun initMediaPlayer() {
        player = MediaPlayer().apply {
            setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            setAudioStreamType(AudioManager.STREAM_MUSIC)

            setOnCompletionListener {
                progressTimer.cancel()
                binding.playerProgressbar.progress = binding.playerProgressbar.max
                binding.playerProgressCurrent.text = binding.playerProgressMax.text
                binding.playPauseBtn.setImageDrawable(getToggleButtonIcon(false))
            }

            setOnPreparedListener {
                if (playOnPreparation) {
                    setupProgressTimer()
                    player?.start()
                }

                playOnPreparation = true
            }
        }
    }

    override fun playRecording(recording: Recording, playOnPrepared: Boolean) {
        resetProgress(recording)
        (binding.recordingsList.adapter as RecordingsAdapter).updateCurrentRecording(recording.id)
        playOnPreparation = playOnPrepared

        player!!.apply {
            reset()

            try {
                val uri = Uri.parse(recording.path)
                when {
                    DocumentsContract.isDocumentUri(context, uri) -> {
                        setDataSource(context, uri)
                    }

                    recording.path.isEmpty() -> {
                        setDataSource(context, getAudioFileContentUri(recording.id.toLong()))
                    }

                    else -> {
                        setDataSource(recording.path)
                    }
                }
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
        val filtered = itemsIgnoringSearch.filter { it.titleWithExtension.contains(text, true) }.toMutableList() as ArrayList<Recording>
        setupAdapter(filtered)
    }

    private fun togglePlayPause() {
        if (getIsPlaying()) {
            pausePlayback()
        } else {
            resumePlayback()
        }
    }

    private fun pausePlayback() {
        player?.pause()
        binding.playPauseBtn.setImageDrawable(getToggleButtonIcon(false))
        progressTimer.cancel()
    }

    private fun resumePlayback() {
        player?.start()
        binding.playPauseBtn.setImageDrawable(getToggleButtonIcon(true))
        setupProgressTimer()
    }

    private fun getToggleButtonIcon(isPlaying: Boolean): Drawable {
        val drawable = if (isPlaying) org.fossify.commons.R.drawable.ic_pause_vector else org.fossify.commons.R.drawable.ic_play_vector
        return resources.getColoredDrawableWithColor(drawable, context.getProperPrimaryColor().getContrastColor())
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
        prevSavePath = context!!.config.saveRecordingsFolder
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
        binding.playPauseBtn.setImageDrawable(getToggleButtonIcon(false))
    }

    fun finishActMode() = getRecordingsAdapter()?.finishActMode()

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun recordingCompleted(event: Events.RecordingCompleted) {
        refreshRecordings()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun recordingMovedToRecycleBin(event: Events.RecordingTrashUpdated) {
        refreshRecordings()
    }
}
