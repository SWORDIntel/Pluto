package org.thoughtcrime.securesms.export.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.export.ChatExportScheduler
import org.thoughtcrime.securesms.export.ui.ExportOptions // Assuming ExportOptions is in this path
import org.thoughtcrime.securesms.export.ui.ExportType
import org.thoughtcrime.securesms.jobs.ScheduledExportJob
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.logging.Log
import java.io.File
import java.util.UUID

class ExportViewModel : ViewModel() {

    companion object {
        private val TAG = Log.tag(ExportViewModel::class.java)
    }

    fun startExportOrSchedule(context: Context, threadId: Long, options: ExportOptions) {
        Log.i(TAG, "startExportOrSchedule called for threadId: $threadId, type: ${options.type}")

        if (options.type == ExportType.ONETIME) {
            // Use ScheduledExportJob for one-time export.
            // Passphrase needs proper handling (e.g., user input or secure generation).
            val passphrase = "test_passphrase_onetime" // Placeholder: Replace with actual passphrase handling

            Log.i(TAG, "Enqueueing ScheduledExportJob for a one-time export for threadId: $threadId")

            val oneTimeJob = ScheduledExportJob(
                threadId = threadId,
                passphrase = passphrase,
                exportFormatName = options.format.name,
                exportDestinationTypeName = options.destination.name,
                apiUrl = options.apiUrl
                // The temporary file path will be generated inside ScheduledExportJob.onRun()
            )
            ApplicationDependencies.getJobManager().add(oneTimeJob)
            Log.i(TAG, "One-time export job enqueued.")

        } else if (options.type == ExportType.SCHEDULED) {
            Log.i(TAG, "Scheduling export for threadId: $threadId with frequency: ${options.frequency}")
            val scheduler = ChatExportScheduler(context.applicationContext)
            // Assuming chatName can be derived or is passed differently. Using threadId for now.
            // The scheduleExportFromOptions method will need to be implemented in ChatExportScheduler
            scheduler.scheduleExportFromOptions(threadId, "Chat $threadId", options)
            Log.i(TAG, "Scheduled export task submitted to ChatExportScheduler.")
        }
    }
}
