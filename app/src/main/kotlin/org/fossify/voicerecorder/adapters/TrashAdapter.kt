package org.fossify.voicerecorder.adapters

import android.view.*
import android.widget.PopupMenu
import android.widget.TextView
import com.qtalk.recyclerviewfastscroller.RecyclerViewFastScroller
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyRecyclerView
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.activities.SimpleActivity
import org.fossify.voicerecorder.databinding.ItemRecordingBinding
import org.fossify.voicerecorder.extensions.deleteRecordings
import org.fossify.voicerecorder.extensions.restoreRecordings
import org.fossify.voicerecorder.interfaces.RefreshRecordingsListener
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.models.Recording
import org.greenrobot.eventbus.EventBus

class TrashAdapter(
    activity: SimpleActivity,
    var recordings: ArrayList<Recording>,
    val refreshListener: RefreshRecordingsListener,
    recyclerView: MyRecyclerView
) :
    MyRecyclerViewAdapter(activity, recyclerView, {}), RecyclerViewFastScroller.OnPopupTextUpdate {

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
        return createViewHolder(ItemRecordingBinding.inflate(layoutInflater, parent, false).root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val recording = recordings[position]
        holder.bindView(recording, true, true) { itemView, layoutPosition ->
            setupView(itemView, recording)
        }
        bindViewHolder(holder)
    }

    override fun getItemCount() = recordings.size

    private fun getItemWithKey(key: Int): Recording? = recordings.firstOrNull { it.id == key }

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

        val recordingsToRestore = recordings.filter { selectedKeys.contains(it.id) } as ArrayList<Recording>
        val positions = getSelectedItemPositions()

        activity.restoreRecordings(recordingsToRestore) { success ->
            if (success) {
                doDeleteAnimation(recordingsToRestore, positions)
                EventBus.getDefault().post(Events.RecordingTrashUpdated())
            }
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

        val recordingsToRemove = recordings.filter { selectedKeys.contains(it.id) } as ArrayList<Recording>
        val positions = getSelectedItemPositions()

        activity.deleteRecordings(recordingsToRemove) { success ->
            if (success) {
                doDeleteAnimation(recordingsToRemove, positions)
            }
        }
    }

    private fun doDeleteAnimation(recordingsToRemove: ArrayList<Recording>, positions: ArrayList<Int>) {
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

    private fun getSelectedItems() = recordings.filter { selectedKeys.contains(it.id) } as ArrayList<Recording>

    private fun setupView(view: View, recording: Recording) {
        ItemRecordingBinding.bind(view).apply {
            root.setupViewBackground(activity)
            recordingFrame.isSelected = selectedKeys.contains(recording.id)

            arrayListOf<TextView>(recordingTitle, recordingDate, recordingDuration, recordingExtension, recordingSize).forEach {
                it.setTextColor(textColor)
            }

            recordingTitle.text = recording.title
            recordingDate.text = recording.timestamp.formatDate(root.context)
            recordingDuration.text = recording.duration.getFormattedDuration()
            recordingExtension.text = recording.extension
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
            menu.findItem(R.id.cab_restore).title = resources.getString(org.fossify.commons.R.string.restore_this_file)
            setOnMenuItemClickListener { item ->
                val recordingId = recording.id
                when (item.itemId) {
                    R.id.cab_restore -> {
                        executeItemMenuOperation(recordingId, removeAfterCallback = false) {
                            restoreRecordings()
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

    private fun executeItemMenuOperation(callId: Int, removeAfterCallback: Boolean = true, callback: () -> Unit) {
        selectedKeys.add(callId)
        callback()
        if (removeAfterCallback) {
            selectedKeys.remove(callId)
        }
    }
}
