package org.fossify.voicerecorder.adapters

import android.annotation.SuppressLint
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import com.qtalk.recyclerviewfastscroller.RecyclerViewFastScroller
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.commons.extensions.formatDate
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getFormattedDuration
import org.fossify.commons.extensions.getPopupMenuTheme
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.openPathIntent
import org.fossify.commons.extensions.setupViewBackground
import org.fossify.commons.extensions.sharePathsIntent
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.views.MyRecyclerView
import org.fossify.voicerecorder.BuildConfig
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.activities.SimpleActivity
import org.fossify.voicerecorder.databinding.ItemRecordingBinding
import org.fossify.voicerecorder.dialogs.DeleteConfirmationDialog
import org.fossify.voicerecorder.dialogs.RenameRecordingDialog
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.deleteRecordings
import org.fossify.voicerecorder.extensions.moveRecordingsToRecycleBin
import org.fossify.voicerecorder.helpers.getAudioFileContentUri
import org.fossify.voicerecorder.interfaces.RefreshRecordingsListener
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.models.Recording
import org.greenrobot.eventbus.EventBus

class RecordingsAdapter(
    activity: SimpleActivity,
    var recordings: ArrayList<Recording>,
    private val refreshListener: RefreshRecordingsListener,
    recyclerView: MyRecyclerView,
    itemClick: (Any) -> Unit
) : MyRecyclerViewAdapter(activity, recyclerView, itemClick),
    RecyclerViewFastScroller.OnPopupTextUpdate {

    var currRecordingId = 0

    init {
        setupDragListener(true)
    }

    override fun getActionMenuId() = R.menu.cab_recordings

    override fun prepareActionMode(menu: Menu) {
        menu.apply {
            findItem(R.id.cab_rename).isVisible = isOneItemSelected()
            findItem(R.id.cab_open_with).isVisible = isOneItemSelected()
        }
    }

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_rename -> renameRecording()
            R.id.cab_share -> shareRecordings()
            R.id.cab_delete -> askConfirmDelete()
            R.id.cab_select_all -> selectAll()
            R.id.cab_open_with -> openRecordingWith()
        }
    }

    override fun getSelectableItemCount() = recordings.size

    override fun getIsItemSelectable(position: Int) = true

    override fun getItemSelectionKey(position: Int): Int? {
        return recordings.getOrNull(position)?.id
    }

    override fun getItemKeyPosition(key: Int): Int {
        return recordings.indexOfFirst { it.id == key }
    }

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return createViewHolder(
            view = ItemRecordingBinding.inflate(layoutInflater, parent, false).root
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val recording = recordings[position]
        holder.bindView(
            any = recording,
            allowSingleClick = true,
            allowLongClick = true
        ) { itemView, _ ->
            setupView(itemView, recording)
        }

        bindViewHolder(holder)
    }

    override fun getItemCount() = recordings.size

    private fun getItemWithKey(key: Int): Recording? = recordings.firstOrNull { it.id == key }

    @SuppressLint("NotifyDataSetChanged")
    fun updateItems(newItems: ArrayList<Recording>) {
        if (newItems.hashCode() != recordings.hashCode()) {
            recordings = newItems
            notifyDataSetChanged()
            finishActMode()
        }
    }

    private fun renameRecording() {
        val recording = getItemWithKey(selectedKeys.first()) ?: return
        RenameRecordingDialog(activity, recording) {
            finishActMode()
            refreshListener.refreshRecordings()
        }
    }

    private fun openRecordingWith() {
        val recording = getItemWithKey(selectedKeys.first()) ?: return
        val path = if (isQPlus()) {
            getAudioFileContentUri(recording.id.toLong()).toString()
        } else {
            recording.path
        }

        activity.openPathIntent(path, false, BuildConfig.APPLICATION_ID, "audio/*")
    }

    private fun shareRecordings() {
        val selectedItems = getSelectedItems()
        val paths = selectedItems.map {
            it.path.ifEmpty {
                getAudioFileContentUri(it.id.toLong()).toString()
            }
        }

        activity.sharePathsIntent(paths, BuildConfig.APPLICATION_ID)
    }

    private fun askConfirmDelete() {
        val itemsCnt = selectedKeys.size
        val firstItem = getSelectedItems().firstOrNull() ?: return
        val items = if (itemsCnt == 1) {
            "\"${firstItem.title}\""
        } else {
            resources.getQuantityString(R.plurals.delete_recordings, itemsCnt, itemsCnt)
        }

        val baseString = if (activity.config.useRecycleBin) {
            org.fossify.commons.R.string.move_to_recycle_bin_confirmation
        } else {
            R.string.delete_recordings_confirmation
        }
        val question = String.format(resources.getString(baseString), items)

        DeleteConfirmationDialog(
            activity = activity,
            message = question,
            showSkipRecycleBinOption = activity.config.useRecycleBin
        ) { skipRecycleBin ->
            ensureBackgroundThread {
                val toRecycleBin = !skipRecycleBin && activity.config.useRecycleBin
                if (toRecycleBin) {
                    moveMediaStoreRecordingsToRecycleBin()
                } else {
                    deleteMediaStoreRecordings()
                }
            }
        }
    }

    private fun deleteMediaStoreRecordings() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val oldRecordingIndex = recordings.indexOfFirst { it.id == currRecordingId }
        val recordingsToRemove = recordings
            .filter { selectedKeys.contains(it.id) } as ArrayList<Recording>

        val positions = getSelectedItemPositions()

        activity.deleteRecordings(recordingsToRemove) { success ->
            if (success) {
                doDeleteAnimation(oldRecordingIndex, recordingsToRemove, positions)
            }
        }
    }

    private fun moveMediaStoreRecordingsToRecycleBin() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val oldRecordingIndex = recordings.indexOfFirst { it.id == currRecordingId }
        val recordingsToRemove = recordings
            .filter { selectedKeys.contains(it.id) } as ArrayList<Recording>

        val positions = getSelectedItemPositions()

        activity.moveRecordingsToRecycleBin(recordingsToRemove) { success ->
            if (success) {
                doDeleteAnimation(oldRecordingIndex, recordingsToRemove, positions)
                EventBus.getDefault().post(Events.RecordingTrashUpdated())
            }
        }
    }

    private fun doDeleteAnimation(
        oldRecordingIndex: Int,
        recordingsToRemove: ArrayList<Recording>,
        positions: ArrayList<Int>
    ) {
        recordings.removeAll(recordingsToRemove.toSet())
        activity.runOnUiThread {
            if (recordings.isEmpty()) {
                refreshListener.refreshRecordings()
                finishActMode()
            } else {
                positions.sortDescending()
                removeSelectedItems(positions)
                if (recordingsToRemove.map { it.id }.contains(currRecordingId)) {
                    val newRecordingIndex = Math.min(oldRecordingIndex, recordings.size - 1)
                    val newRecording = recordings[newRecordingIndex]
                    refreshListener.playRecording(newRecording, false)
                }
            }
        }
    }

    fun updateCurrentRecording(newId: Int) {
        val oldId = currRecordingId
        currRecordingId = newId
        notifyItemChanged(recordings.indexOfFirst { it.id == oldId })
        notifyItemChanged(recordings.indexOfFirst { it.id == newId })
    }

    private fun getSelectedItems(): ArrayList<Recording> {
        return recordings.filter { selectedKeys.contains(it.id) } as ArrayList<Recording>
    }

    private fun setupView(view: View, recording: Recording) {
        ItemRecordingBinding.bind(view).apply {
            root.setupViewBackground(activity)
            recordingFrame.isSelected = selectedKeys.contains(recording.id)

            arrayListOf(
                recordingTitle,
                recordingDate,
                recordingDuration,
                recordingSize
            ).forEach {
                it.setTextColor(textColor)
            }

            if (recording.id == currRecordingId) {
                recordingTitle.setTextColor(root.context.getProperPrimaryColor())
            }

            recordingTitle.text = recording.title
            recordingDate.text = recording.timestamp.formatDate(root.context)
            recordingDuration.text = recording.duration.getFormattedDuration()
            recordingSize.text = recording.size.formatSize()

            overflowMenuIcon.drawable.apply {
                mutate()
                setTint(activity.getProperTextColor())
            }

            overflowMenuIcon.setOnClickListener {
                showPopupMenu(overflowMenuAnchor, recording)
            }
        }
    }

    override fun onChange(position: Int) = recordings.getOrNull(position)?.title ?: ""

    @SuppressLint("NotifyDataSetChanged")
    private fun showPopupMenu(view: View, recording: Recording) {
        if (selectedKeys.isNotEmpty()) {
            selectedKeys.clear()
            notifyDataSetChanged()
        }

        finishActMode()
        val theme = activity.getPopupMenuTheme()
        val contextTheme = ContextThemeWrapper(activity, theme)

        PopupMenu(contextTheme, view, Gravity.END).apply {
            inflate(getActionMenuId())
            menu.findItem(R.id.cab_select_all).isVisible = false
            setOnMenuItemClickListener { item ->
                val recordingId = recording.id
                when (item.itemId) {
                    R.id.cab_rename -> {
                        executeItemMenuOperation(recordingId) {
                            renameRecording()
                        }
                    }

                    R.id.cab_share -> {
                        executeItemMenuOperation(recordingId) {
                            shareRecordings()
                        }
                    }

                    R.id.cab_open_with -> {
                        executeItemMenuOperation(recordingId) {
                            openRecordingWith()
                        }
                    }

                    R.id.cab_delete -> {
                        executeItemMenuOperation(recordingId, removeAfterCallback = false) {
                            askConfirmDelete()
                        }
                    }
                }

                true
            }
            show()
        }
    }

    private fun executeItemMenuOperation(
        callId: Int,
        removeAfterCallback: Boolean = true,
        callback: () -> Unit
    ) {
        selectedKeys.add(callId)
        callback()
        if (removeAfterCallback) {
            selectedKeys.remove(callId)
        }
    }
}
