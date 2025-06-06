package org.thoughtcrime.securesms.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.export.ChatExportScheduler
import org.thoughtcrime.securesms.export.ui.ExportDestination
import org.thoughtcrime.securesms.export.ui.ExportFormat
import org.thoughtcrime.securesms.export.ui.ExportFrequency
import org.thoughtcrime.securesms.export.ui.ExportOptions
import org.thoughtcrime.securesms.export.ui.ExportType
import org.thoughtcrime.securesms.jobs.ScheduledExportJob
import org.thoughtcrime.securesms.logging.Log

class DebugExportTriggerReceiver : BroadcastReceiver() {

    companion object {
        private val TAG = Log.tag(DebugExportTriggerReceiver::class.java)
        const val ACTION_TRIGGER_EXPORT = "org.thoughtcrime.securesms.DEBUG_TRIGGER_EXPORT"
        const val EXTRA_EXPORT_TYPE = "export_type" // "onetime" or "scheduled"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) {
            Log.w(TAG, "DebugExportTriggerReceiver invoked in a non-debug build. Ignoring.")
            return
        }

        Log.i(TAG, "Debug export trigger received. Action: ${intent.action}")

        if (intent.action != ACTION_TRIGGER_EXPORT) {
            Log.w(TAG, "Received intent with incorrect action: ${intent.action}")
            return
        }

        val appContext = context.applicationContext
        val threadId = 1L // Hardcoded threadId for debugging
        val fixedPassphrase = "debug_export_passphrase"
        val debugApiUrl = "https://debug.example.com/upload" // Placeholder debug API URL

        val exportTypeToTrigger = intent.getStringExtra(EXTRA_EXPORT_TYPE)?.lowercase() ?: "onetime"
        Log.d(TAG, "Requested export type from intent: $exportTypeToTrigger")


        if (exportTypeToTrigger == "onetime") {
            val oneTimeOptions = ExportOptions(
                format = ExportFormat.JSON,
                destination = ExportDestination.API_ENDPOINT, // Or LOCAL_FILE for testing local path
                apiUrl = debugApiUrl,
                type = ExportType.ONETIME,
                frequency = null
            )
            Log.i(TAG, "Initiating ONE-TIME debug export for threadId: $threadId with options: $oneTimeOptions")

            val jobManager = ApplicationDependencies.getJobManager(appContext)
            val exportJob = ScheduledExportJob(
                threadId = threadId,
                passphrase = fixedPassphrase,
                exportFormatName = oneTimeOptions.format.name,
                exportDestinationTypeName = oneTimeOptions.destination.name,
                apiUrl = oneTimeOptions.apiUrl
            )
            jobManager.add(exportJob)
            Log.i(TAG, "One-time ScheduledExportJob enqueued via JobManager.")

        } else if (exportTypeToTrigger == "scheduled") {
            val scheduledOptions = ExportOptions(
                format = ExportFormat.JSON,
                destination = ExportDestination.API_ENDPOINT,
                apiUrl = debugApiUrl,
                type = ExportType.SCHEDULED,
                frequency = ExportFrequency.DAILY // Hardcoded frequency for debugging
            )
            Log.i(TAG, "Initiating SCHEDULED debug export for threadId: $threadId with options: $scheduledOptions")

            // ChatExportScheduler needs WorkManager, KeyValueDatabase, Gson - assume these are available via ApplicationDependencies or a similar DI setup.
            // For simplicity, if ChatExportScheduler constructor is complex, this might need adjustment.
            // Correctly instantiate dependencies for ChatExportScheduler.
            try {
                val workManager = androidx.work.WorkManager.getInstance(appContext)
                val keyValueDatabase = org.thoughtcrime.securesms.database.SignalDatabase.getKeyValue(appContext) // Corrected KVD access
                val gson = com.google.gson.Gson() // Using new Gson() as JsonUtil.getGson() wasn't found

                val scheduler = ChatExportScheduler(
                    appContext,
                    workManager,
                    keyValueDatabase,
                    gson
                )
                scheduler.scheduleExportFromOptions(threadId, "DebugScheduledChat_$threadId", scheduledOptions)
                Log.i(TAG, "Scheduled export submitted to ChatExportScheduler.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to instantiate or use ChatExportScheduler for debug scheduled export.", e)
            }
        } else {
            Log.w(TAG, "Unknown export type specified in intent extra: $exportTypeToTrigger")
        }
    }
}
