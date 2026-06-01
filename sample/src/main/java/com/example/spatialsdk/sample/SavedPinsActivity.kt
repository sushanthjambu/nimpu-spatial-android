package com.example.spatialsdk.sample

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.nimpu.spatial.sdk.CloudPinDeleteResult
import com.nimpu.spatial.sdk.LocalPinUploadResult
import com.nimpu.spatial.sdk.NimpuPin
import com.nimpu.spatial.sdk.NimpuSpatialSdk
import com.nimpu.spatial.sdk.PinUploadStatus
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

class SavedPinsActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var adapter: SavedPinsAdapter
    private val savedPins = mutableListOf<NimpuPin>()
    private var loadGeneration = 0
    private val dateFormatter: DateFormat by lazy {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saved_pins)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Saved Pins"

        listView = findViewById(R.id.list_saved_pins)
        emptyView = findViewById(R.id.tv_empty_saved_pins)
        adapter = SavedPinsAdapter()
        listView.adapter = adapter
        listView.emptyView = emptyView
    }

    override fun onResume() {
        super.onResume()
        reloadPins()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun reloadPins() {
        val generation = ++loadGeneration
        emptyView.text = "Loading saved pins..."
        savedPins.clear()
        adapter.notifyDataSetChanged()

        val appContext = applicationContext
        thread(name = "NimpuSavedPinsLoad") {
            val groupedPins = LocalPinDisplayGrouper.group(NimpuSpatialSdk.listLocalPins(appContext))
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != loadGeneration) return@runOnUiThread
                emptyView.text = "No saved pins yet."
                savedPins.clear()
                savedPins.addAll(groupedPins)
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun confirmDelete(pin: NimpuPin) {
        val localPinId = pin.localPinId ?: return
        val cloudPinId = pin.cloudPinId?.takeIf { it.isNotBlank() }
        if (cloudPinId != null) {
            confirmDeleteCloudBackedPin(pin, localPinId, cloudPinId)
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Delete saved pin?")
            .setMessage("Delete ${pin.displayName}? This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                deleteLocalPin(pin, localPinId)
            }
            .show()
    }

    private fun confirmDeleteCloudBackedPin(
        pin: NimpuPin,
        localPinId: String,
        cloudPinId: String
    ) {
        AlertDialog.Builder(this)
            .setTitle("Delete saved pin?")
            .setMessage(
                "Delete ${pin.displayName}? You can remove only this phone's saved copy, " +
                    "or soft-delete the cloud pin too."
            )
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete local copy") { _, _ ->
                deleteLocalPin(pin, localPinId)
            }
            .setPositiveButton("Delete local + cloud") { _, _ ->
                deleteCloudAndLocalPin(pin, localPinId, cloudPinId)
            }
            .show()
    }

    private fun deleteLocalPin(pin: NimpuPin, localPinId: String) {
        val deleted = NimpuSpatialSdk.deleteLocalPin(this, localPinId)
        if (deleted) {
            Toast.makeText(this, "Deleted ${pin.displayName}", Toast.LENGTH_SHORT).show()
            reloadPins()
        } else {
            Toast.makeText(this, "Could not delete ${pin.displayName}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteCloudAndLocalPin(
        pin: NimpuPin,
        localPinId: String,
        cloudPinId: String
    ) {
        Toast.makeText(this, "Deleting cloud pin...", Toast.LENGTH_SHORT).show()
        NimpuSpatialSdk.deleteCloudPin(cloudPinId) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is CloudPinDeleteResult.Success -> {
                        val deletedLocal = NimpuSpatialSdk.deleteLocalPin(this, localPinId)
                        if (deletedLocal) {
                            Toast.makeText(
                                this,
                                "Deleted ${pin.displayName} locally and in cloud",
                                Toast.LENGTH_SHORT
                            ).show()
                            reloadPins()
                        } else {
                            Toast.makeText(
                                this,
                                "Cloud pin deleted, but local copy could not be deleted",
                                Toast.LENGTH_LONG
                            ).show()
                            reloadPins()
                        }
                    }
                    is CloudPinDeleteResult.Failed -> {
                        confirmDeleteLocalAfterCloudFailure(pin, localPinId, result.error)
                    }
                }
            }
        }
    }

    private fun confirmDeleteLocalAfterCloudFailure(
        pin: NimpuPin,
        localPinId: String,
        error: String
    ) {
        AlertDialog.Builder(this)
            .setTitle("Cloud delete failed")
            .setMessage("Could not delete the cloud pin: $error\n\nDelete the local copy anyway?")
            .setNegativeButton("Keep local copy", null)
            .setPositiveButton("Delete local copy") { _, _ ->
                deleteLocalPin(pin, localPinId)
            }
            .show()
    }

    private fun showUploadDialog(pin: NimpuPin) {
        val localPinId = pin.localPinId ?: return
        val defaultName = NimpuSpatialSdk.uploadDisplayNameOrDefault(this, pin)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val nameInput = EditText(this).apply {
            setText(defaultName)
            selectAll()
            hint = "Pin name"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val statusText = TextView(this).apply {
            text = "Name this pin before uploading."
            setPadding(0, 20, 0, 0)
        }
        val progress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        container.addView(nameInput)
        container.addView(statusText)
        container.addView(progress)

        val dialog = AlertDialog.Builder(this)
            .setTitle(uploadDialogTitle(pin))
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Upload", null)
            .create()

        dialog.setOnShowListener {
            val uploadButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val cancelButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            uploadButton.setOnClickListener {
                val displayName = nameInput.text?.toString()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: defaultName
                nameInput.isEnabled = false
                uploadButton.isEnabled = false
                cancelButton.visibility = View.GONE
                progress.visibility = View.VISIBLE
                statusText.text = "Uploading pin..."

                NimpuSpatialSdk.uploadLocalPin(
                    context = this,
                    localPinId = localPinId,
                    displayName = displayName
                ) { result ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        progress.visibility = View.GONE
                        when (result) {
                            is LocalPinUploadResult.Uploaded -> {
                                statusText.text = "Upload complete."
                                Toast.makeText(this, "Pin uploaded.", Toast.LENGTH_SHORT).show()
                            }
                            is LocalPinUploadResult.Failed -> {
                                statusText.text = "Upload failed: ${result.error}"
                                Toast.makeText(this, "Upload failed.", Toast.LENGTH_LONG).show()
                            }
                        }
                        reloadPins()
                        uploadButton.text = "Done"
                        uploadButton.isEnabled = true
                        uploadButton.setOnClickListener { dialog.dismiss() }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun uploadDialogTitle(pin: NimpuPin): String {
        return if (pin.uploadStatus == PinUploadStatus.FAILED) {
            "Retry Upload"
        } else {
            "Upload Pin"
        }
    }

    private fun canUpload(pin: NimpuPin): Boolean {
        return pin.localPinId != null &&
            (pin.uploadStatus == PinUploadStatus.LOCAL_ONLY ||
                pin.uploadStatus == PinUploadStatus.FAILED ||
                pin.uploadStatus == PinUploadStatus.UPLOADING)
    }

    private inner class SavedPinsAdapter : BaseAdapter() {
        override fun getCount(): Int = savedPins.size

        override fun getItem(position: Int): NimpuPin = savedPins[position]

        override fun getItemId(position: Int): Long = getItem(position).createdAt

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_saved_pin, parent, false)

            val pin = getItem(position)
            val title = view.findViewById<TextView>(R.id.tv_pin_id)
            val subtitle = view.findViewById<TextView>(R.id.tv_pin_details)
            val uploadButton = view.findViewById<ImageButton>(R.id.btn_upload_pin)
            val deleteButton = view.findViewById<ImageButton>(R.id.btn_delete_pin)

            title.text = pin.displayName
            val createdAt = dateFormatter.format(Date(pin.createdAt))
            subtitle.text = savedPinSubtitle(pin, createdAt)

            uploadButton.visibility = if (canUpload(pin)) View.VISIBLE else View.GONE
            uploadButton.contentDescription = uploadDialogTitle(pin)
            uploadButton.setOnClickListener {
                showUploadDialog(pin)
            }

            deleteButton.setOnClickListener {
                confirmDelete(pin)
            }

            return view
        }
    }

    private fun savedPinSubtitle(
        pin: NimpuPin,
        createdAt: String
    ): String {
        val status = when (pin.uploadStatus) {
            PinUploadStatus.UPLOADED -> "Uploaded"
            PinUploadStatus.FAILED -> "Upload failed"
            PinUploadStatus.UPLOADING -> "Upload pending"
            else -> "Local only"
        }
        return "$createdAt - ${pin.pointCount} points - $status"
    }
}
