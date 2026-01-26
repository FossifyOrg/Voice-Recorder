package org.fossify.voicerecorder.fragments

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.updateTextColors
import org.fossify.voicerecorder.activities.SimpleActivity
import org.fossify.voicerecorder.adapters.TrashAdapter
import org.fossify.voicerecorder.databinding.FragmentTrashBinding
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.interfaces.RefreshRecordingsListener
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.store.Recording
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import kotlin.collections.isNotEmpty

class TrashFragment(
    context: Context,
    attributeSet: AttributeSet
) : MyViewPagerFragment(context, attributeSet), RefreshRecordingsListener {

    private var itemsIgnoringSearch = ArrayList<Recording>()
    private var lastSearchQuery = ""
    private var bus: EventBus? = null
    private var prevSaveFolder: Uri? = null
    private lateinit var binding: FragmentTrashBinding

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentTrashBinding.bind(this)
    }

    override fun onResume() {
        setupColors()
        if (prevSaveFolder != null && context!!.config.saveRecordingsFolder != prevSaveFolder) {
            loadRecordings(trashed = true)
        } else {
            getRecordingsAdapter()?.updateTextColor(context.getProperTextColor())
        }

        storePrevPath()
    }

    override fun onDestroy() {
        bus?.unregister(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        bus = EventBus.getDefault()
        bus!!.register(this)
        setupColors()
        loadRecordings(trashed = true)
        storePrevPath()
    }

    override fun refreshRecordings() = loadRecordings(trashed = true)

    override fun playRecording(recording: Recording, playOnPrepared: Boolean) {}

    override fun onLoadingStart() {
        if (itemsIgnoringSearch.isEmpty()) {
            binding.loadingIndicator.show()
        } else {
            binding.loadingIndicator.hide()
        }
    }

    override fun onLoadingEnd(recordings: ArrayList<Recording>) {
        binding.loadingIndicator.hide()
        binding.trashPlaceholder.beVisibleIf(recordings.isEmpty())
        itemsIgnoringSearch = recordings
        setupAdapter(itemsIgnoringSearch)
    }

    private fun setupAdapter(recordings: ArrayList<Recording>) {
        binding.trashFastscroller.beVisibleIf(recordings.isNotEmpty())
        if (recordings.isEmpty()) {
            val stringId = if (lastSearchQuery.isEmpty()) {
                org.fossify.commons.R.string.recycle_bin_empty
            } else {
                org.fossify.commons.R.string.no_items_found
            }

            binding.trashPlaceholder.text = context.getString(stringId)
        }

        val adapter = getRecordingsAdapter()
        if (adapter == null) {
            TrashAdapter(context as SimpleActivity, recordings, this, binding.trashList).apply {
                binding.trashList.adapter = this
            }

            if (context.areSystemAnimationsEnabled) {
                binding.trashList.scheduleLayoutAnimation()
            }
        } else {
            adapter.updateItems(recordings)
        }
    }

    fun onSearchTextChanged(text: String) {
        lastSearchQuery = text
        val filtered = itemsIgnoringSearch.filter { it.title.contains(text, true) }
            .toMutableList() as ArrayList<Recording>
        setupAdapter(filtered)
    }

    private fun getRecordingsAdapter() = binding.trashList.adapter as? TrashAdapter

    private fun storePrevPath() {
        prevSaveFolder = context!!.config.saveRecordingsFolder
    }

    private fun setupColors() {
        val properPrimaryColor = context.getProperPrimaryColor()
        binding.trashFastscroller.updateColors(properPrimaryColor)
        context.updateTextColors(binding.trashHolder)
    }

    fun finishActMode() = getRecordingsAdapter()?.finishActMode()

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun recordingMovedToRecycleBin(@Suppress("UNUSED_PARAMETER") event: Events.RecordingTrashUpdated) {
        refreshRecordings()
    }
}
