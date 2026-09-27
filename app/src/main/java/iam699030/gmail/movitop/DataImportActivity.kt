package iam699030.gmail.movitop

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import iam699030.gmail.movitop.data.DataImportManager
import kotlinx.coroutines.launch

/**
 * Lets the user pick a MOTIS data package (a zip produced offline by
 * package_motis_data.sh, copied to the device over USB/SD card — never
 * downloaded) and swaps it in for the running engine's data. This is how a
 * GTFS/OSM refresh reaches installed devices without shipping a new APK.
 */
class DataImportActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var pickFileButton: MaterialButton

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) startImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_import)

        statusText = findViewById(R.id.importStatus)
        progress = findViewById(R.id.importProgress)
        pickFileButton = findViewById(R.id.pickFileButton)

        pickFileButton.setOnClickListener {
            pickFileLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
    }

    private fun startImport(uri: Uri) {
        pickFileButton.isEnabled = false
        progress.visibility = View.VISIBLE
        statusText.text = getString(R.string.data_import_in_progress)

        lifecycleScope.launch {
            val result = DataImportManager(applicationContext).import(uri)
            progress.visibility = View.GONE
            pickFileButton.isEnabled = true
            statusText.text = when (result) {
                is DataImportManager.Result.Success ->
                    getString(R.string.data_import_success, result.filesImported)
                is DataImportManager.Result.Failure ->
                    getString(R.string.data_import_failure, result.reason)
            }
        }
    }
}
