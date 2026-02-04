package org.fossify.voicerecorder.adapters

import android.annotation.SuppressLint
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import com.qtalk.recyclerviewfastscroller.RecyclerViewFastScroller
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.formatDate
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getFormattedDuration
import org.fossify.commons.extensions.setupViewBackground
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyRecyclerView
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.activities.SimpleActivity
import org.fossify.voicerecorder.databinding.ItemRecordingBinding
import org.fossify.voicerecorder.extensions.recordingStore
import org.fossify.voicerecorder.interfaces.RefreshRecordingsListener
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.store.Recording
import org.greenrobot.eventbus.EventBus

class TrashAdapter(
    activity: SimpleActivity, var recordings: ArrayList<Recording>, private val refreshListener: RefreshRecordingsListener, recyclerView: MyRecyclerView
) : MyRecyclerViewAdapter(activity, recyclerView, {}), RecyclerViewFastScroller.OnPopupTextUpdate {

    init {
        setupDragListener(true)
    }

    override fun getActionMenuId() = R.menu.cab_trash

    override fun prepareActionMode(menu: Menu) {}

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_restore -> restoreRecordings()
            R.id.cab_delete -> askConfirmDelete()
            R.id.cab_select_all -> selectAll()
        }
    }

    override fun getSelectableItemCount() = recordings.size

    override fun getIsItemSelectable(position: Int) = true

    override fun getItemSelectionKey(position: Int) = recordings.getOrNull(position)?.id

    override fun getItemKeyPosition(key: Int) = recordings.indexOfFirst { it.id == key }

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

    private fun restoreRecordings() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val recordingsToRestore = recordings.filter { selectedKeys.contains(it.id) }.toList()

        val positions = getSelectedItemPositions()

        ensureBackgroundThread {
            activity.recordingStore.restore(recordingsToRestore)

            doDeleteAnimation(recordingsToRestore, positions)
            EventBus.getDefault().post(Events.RecordingTrashUpdated())
        }
    }

    private fun askConfirmDelete() {
        val itemsCnt = selectedKeys.size
        val firstItem = getSelectedItems().firstOrNull() ?: return
        val items = if (itemsCnt == 1) {
            "\"${firstItem.title}\""
        } else {
            resources.getQuantityString(R.plurals.delete_recordings, itemsCnt, itemsCnt)
        }

        val baseString = R.string.delete_recordings_confirmation
        val question = String.format(resources.getString(baseString), items)

        ConfirmationDialog(activity, question) {
            ensureBackgroundThread {
                deleteMediaStoreRecordings()
            }
        }
    }

    private fun deleteMediaStoreRecordings() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val recordingsToRemove = recordings.filter { selectedKeys.contains(it.id) }.toList()

        val positions = getSelectedItemPositions()

        ensureBackgroundThread {
            activity.recordingStore.delete(recordingsToRemove)
            doDeleteAnimation(recordingsToRemove, positions)
        }
    }

    private fun doDeleteAnimation(
        recordingsToRemove: List<Recording>, positions: ArrayList<Int>
    ) {
        recordings.removeAll(recordingsToRemove.toSet())
        activity.runOnUiThread {
            if (recordings.isEmpty()) {
                refreshListener.refreshRecordings()
                finishActMode()
            } else {
                positions.sortDescending()
                removeSelectedItems(positions)
            }
        }
    }

    private fun getSelectedItems(): ArrayList<Recording> {
        return recordings.filter { selectedKeys.contains(it.id) } as ArrayList<Recording>
    }

    private fun setupView(view: View, recording: Recording) {
        ItemRecordingBinding.bind(view).apply {
            root.setupViewBackground(activity)
            recordingFrame.isSelected = selectedKeys.contains(recording.id)

            arrayListOf(
                recordingTitle, recordingDate, recordingDuration, recordingSize
            ).forEach {
                it.setTextColor(textColor)
            }

            recordingTitle.text = recording.title
            recordingDate.text = recording.timestamp.formatDate(root.context)
            recordingDuration.text = recording.duration.getFormattedDuration()
            recordingSize.text = recording.size.formatSize()
        }
    }

    override fun onChange(position: Int) = recordings.getOrNull(position)?.title ?: ""
}
