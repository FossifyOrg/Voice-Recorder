package org.fossify.voicerecorder.adapters

import android.annotation.SuppressLint
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import com.qtalk.recyclerviewfastscroller.RecyclerViewFastScroller
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.formatDate
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getFormattedDuration
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.openPathIntent
import org.fossify.commons.extensions.setupViewBackground
import org.fossify.commons.extensions.sharePathsIntent
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyRecyclerView
import org.fossify.voicerecorder.BuildConfig
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.activities.ExternalStoragePermission
import org.fossify.voicerecorder.activities.SimpleActivity
import org.fossify.voicerecorder.databinding.ItemRecordingBinding
import org.fossify.voicerecorder.dialogs.DeleteConfirmationDialog
import org.fossify.voicerecorder.dialogs.RenameRecordingDialog
import org.fossify.voicerecorder.extensions.buildShareTranscriptJsonIntent
import org.fossify.voicerecorder.extensions.buildShareTranscriptTextIntent
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.recordingStore
import org.fossify.voicerecorder.extensions.toShareableText
import org.fossify.voicerecorder.interfaces.RefreshRecordingsListener
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.store.Recording
import org.fossify.voicerecorder.store.TranscriptStore
import org.greenrobot.eventbus.EventBus
import kotlin.math.min

class RecordingsAdapter(
    activity: SimpleActivity,
    var recordings: MutableList<Recording>,
    private val refreshListener: RefreshRecordingsListener,
    recyclerView: MyRecyclerView,
    private val onTranscriptIndicatorClick: (Recording) -> Unit,
    itemClick: (Any) -> Unit
) : MyRecyclerViewAdapter(activity, recyclerView, itemClick), RecyclerViewFastScroller.OnPopupTextUpdate {

    var currRecordingId = 0

    /** Map of recording id → first-line transcript preview. Recordings absent from this map have no transcript. */
    var transcriptPreviews: Map<Int, String> = emptyMap()
        set(value) {
            field = value
            @SuppressLint("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

    init {
        setupDragListener(true)
    }

    override fun getActionMenuId() = R.menu.cab_recordings

    override fun prepareActionMode(menu: Menu) {
        val selected = getSelectedItems()
        val anyHasTranscript = selected.any { it.id in transcriptPreviews }
        menu.findItem(R.id.cab_delete_transcript).isVisible = anyHasTranscript
    }

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) return
        when (id) {
            R.id.cab_share -> shareRecordings(getSelectedItems())
            R.id.cab_delete -> askConfirmDelete(getSelectedItems())
            R.id.cab_select_all -> selectAll()
            R.id.cab_delete_transcript -> askConfirmDeleteTranscripts(
                getSelectedItems().filter { it.id in transcriptPreviews }
            )
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
            any = recording, allowSingleClick = true, allowLongClick = true
        ) { itemView, _ ->
            setupView(itemView, recording)
        }

        bindViewHolder(holder)
    }

    override fun getItemCount() = recordings.size

    @SuppressLint("NotifyDataSetChanged")
    fun updateItems(newItems: ArrayList<Recording>) {
        if (newItems.hashCode() != recordings.hashCode()) {
            recordings = newItems
            notifyDataSetChanged()
            finishActMode()
        }
    }

    private fun renameRecording(recording: Recording) {
        RenameRecordingDialog(activity, recording) {
            finishActMode()
            refreshListener.refreshRecordings()
        }
    }

    private fun openRecordingWith(recording: Recording) {
        activity.openPathIntent(
            path = recording.uri.toString(),
            forceChooser = true,
            applicationId = BuildConfig.APPLICATION_ID,
            forceMimeType = "audio/*"
        )
    }

    private fun shareRecordings(items: List<Recording>) {
        if (items.isEmpty()) return
        val paths = items.map { it.uri.toString() }
        activity.sharePathsIntent(paths, BuildConfig.APPLICATION_ID)
    }

    private fun askConfirmDelete(items: List<Recording>) {
        if (items.isEmpty()) return
        val displayName = if (items.size == 1) {
            "\"${items.first().title}\""
        } else {
            resources.getQuantityString(R.plurals.delete_recordings, items.size, items.size)
        }

        val baseString = if (activity.config.useRecycleBin) {
            org.fossify.commons.R.string.move_to_recycle_bin_confirmation
        } else {
            R.string.delete_recordings_confirmation
        }
        val question = String.format(resources.getString(baseString), displayName)

        DeleteConfirmationDialog(
            activity = activity, message = question, showSkipRecycleBinOption = activity.config.useRecycleBin
        ) { skipRecycleBin ->
            ensureBackgroundThread {
                val toRecycleBin = !skipRecycleBin && activity.config.useRecycleBin
                if (toRecycleBin) {
                    trashRecordings(items)
                } else {
                    deleteRecordings(items)
                }
            }
        }
    }

    private fun deleteRecordings(items: List<Recording>) {
        if (items.isEmpty()) return
        runWithWriteExternalStoragePermission {
            val oldRecordingIndex = recordings.indexOfFirst { it.id == currRecordingId }
            val positions = items.mapNotNull { item ->
                recordings.indexOfFirst { it.id == item.id }.takeIf { it >= 0 }
            }
            ensureBackgroundThread {
                activity.recordingStore.delete(items)
                doDeleteAnimation(oldRecordingIndex, items, ArrayList(positions))
            }
        }
    }

    private fun trashRecordings(items: List<Recording>) {
        if (items.isEmpty()) return
        runWithWriteExternalStoragePermission {
            val oldRecordingIndex = recordings.indexOfFirst { it.id == currRecordingId }
            val positions = items.mapNotNull { item ->
                recordings.indexOfFirst { it.id == item.id }.takeIf { it >= 0 }
            }
            ensureBackgroundThread {
                activity.recordingStore.trash(items)
                doDeleteAnimation(oldRecordingIndex, items, ArrayList(positions))
                EventBus.getDefault().post(Events.RecordingTrashUpdated())
            }
        }
    }

    private fun doDeleteAnimation(
        oldRecordingIndex: Int, recordingsToRemove: List<Recording>, positions: ArrayList<Int>
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
                    val newRecordingIndex = min(oldRecordingIndex, recordings.size - 1)
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
                recordingTitle, recordingDate, recordingDuration, recordingSize, transcriptPreview
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

            transcriptIndicator.visibility = View.VISIBLE
            val preview = transcriptPreviews[recording.id]
            if (preview != null) {
                transcriptPreview.text = preview
                transcriptPreview.setTypeface(null, android.graphics.Typeface.ITALIC)
                transcriptPreview.alpha = TRANSCRIPT_PREVIEW_ALPHA
                transcriptIndicatorIcon.alpha = 1f
                transcriptIndicatorIcon.setColorFilter(root.context.getProperPrimaryColor())
            } else {
                transcriptPreview.text = activity.getString(R.string.transcribe)
                transcriptPreview.setTypeface(null, android.graphics.Typeface.NORMAL)
                transcriptPreview.alpha = TRANSCRIBE_PROMPT_ALPHA
                transcriptIndicatorIcon.alpha = TRANSCRIBE_PROMPT_ALPHA
                transcriptIndicatorIcon.clearColorFilter()
            }
            transcriptIndicator.setOnClickListener { onTranscriptIndicatorClick(recording) }
            transcriptIndicator.setOnLongClickListener {
                // Forward long-press to the row so it triggers selection mode like the rest.
                view.performLongClick()
            }

            recordingOverflow.setColorFilter(textColor)
            recordingOverflow.setOnClickListener { showRowOverflowMenu(it, recording) }
        }
    }

    companion object {
        private const val TRANSCRIPT_PREVIEW_ALPHA = 0.7f
        private const val TRANSCRIBE_PROMPT_ALPHA = 0.5f
    }

    override fun onChange(position: Int) = recordings.getOrNull(position)?.title ?: ""

    // Runs the callback only after the WRITE_STORAGE_PERMISSON has been granted or if running on a SDK that no
    // longer requires it.
    private fun runWithWriteExternalStoragePermission(callback: () -> Unit) = (activity as SimpleActivity?)?.run {
        handleExternalStoragePermission(ExternalStoragePermission.WRITE) { granted ->
            if (granted == true) {
                callback()
            }
        }
    }

    private fun transcriptStore() = TranscriptStore(activity, activity.config.saveRecordingsFolder)

    private fun shareTranscriptAsText(recording: Recording) {
        ensureBackgroundThread {
            val transcript = transcriptStore().read(recording)
            activity.runOnUiThread {
                if (transcript == null) {
                    activity.toast(R.string.transcript_failed)
                    return@runOnUiThread
                }
                val text = transcript.toShareableText(recording)
                activity.startActivity(
                    activity.buildShareTranscriptTextIntent(text, recording.title)
                )
            }
        }
    }

    private fun shareTranscriptAsJson(recording: Recording) {
        ensureBackgroundThread {
            val uri = transcriptStore().sidecarUri(recording)
            activity.runOnUiThread {
                if (uri == null) {
                    activity.toast(R.string.transcript_failed)
                    return@runOnUiThread
                }
                activity.startActivity(
                    activity.buildShareTranscriptJsonIntent(uri, recording.title)
                )
            }
        }
    }

    private fun copyTranscript(recording: Recording) {
        ensureBackgroundThread {
            val transcript = transcriptStore().read(recording)
            activity.runOnUiThread {
                if (transcript == null) {
                    activity.toast(R.string.transcript_failed)
                    return@runOnUiThread
                }
                activity.copyToClipboard(transcript.toShareableText(recording))
                activity.toast(R.string.transcript_copied)
            }
        }
    }

    private fun askConfirmDeleteTranscripts(items: List<Recording>) {
        if (items.isEmpty()) return
        ConfirmationDialog(
            activity = activity,
            message = activity.getString(R.string.delete_transcript) + "?",
            positive = org.fossify.commons.R.string.yes,
            negative = org.fossify.commons.R.string.no,
        ) {
            ensureBackgroundThread {
                val store = transcriptStore()
                items.forEach { store.delete(it) }
                activity.runOnUiThread {
                    finishActMode()
                    refreshListener.refreshRecordings()
                }
            }
        }
    }

    /**
     * Inflates the per-row 3-dot popup, hides transcript-related items if the recording
     * has none, and routes selections to the matching single-item action.
     */
    private fun showRowOverflowMenu(anchor: View, recording: Recording) {
        val hasTranscript = recording.id in transcriptPreviews
        val popup = PopupMenu(activity, anchor)
        popup.menuInflater.inflate(R.menu.menu_recording_row, popup.menu)
        popup.menu.findItem(R.id.row_copy_transcript).isVisible = hasTranscript
        popup.menu.findItem(R.id.row_share_transcript_text).isVisible = hasTranscript
        popup.menu.findItem(R.id.row_share_transcript_json).isVisible = hasTranscript
        popup.menu.findItem(R.id.row_delete_transcript).isVisible = hasTranscript
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.row_rename -> { renameRecording(recording); true }
                R.id.row_open_with -> { openRecordingWith(recording); true }
                R.id.row_share_audio -> { shareRecordings(listOf(recording)); true }
                R.id.row_delete_audio -> { askConfirmDelete(listOf(recording)); true }
                R.id.row_copy_transcript -> { copyTranscript(recording); true }
                R.id.row_share_transcript_text -> { shareTranscriptAsText(recording); true }
                R.id.row_share_transcript_json -> { shareTranscriptAsJson(recording); true }
                R.id.row_delete_transcript -> { askConfirmDeleteTranscripts(listOf(recording)); true }
                else -> false
            }
        }
        popup.show()
    }
}
