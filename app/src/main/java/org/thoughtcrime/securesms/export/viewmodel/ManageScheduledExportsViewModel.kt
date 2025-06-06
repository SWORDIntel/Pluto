package org.thoughtcrime.securesms.export.viewmodel

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.thoughtcrime.securesms.export.ChatExportScheduler
import org.thoughtcrime.securesms.export.model.PersistedScheduleData
import org.thoughtcrime.securesms.logging.Log
import org.thoughtcrime.securesms.util.concurrent.SignalExecutors

class ManageScheduledExportsViewModel : ViewModel() {

    companion object {
        private val TAG = Log.tag(ManageScheduledExportsViewModel::class.java)
    }

    private val scheduledExportsLiveData = MutableLiveData<List<PersistedScheduleData>>()

    fun getScheduledExports(context: Context): LiveData<List<PersistedScheduleData>> {
        loadAllScheduledExports(context)
        return scheduledExportsLiveData
    }

    private fun loadAllScheduledExports(context: Context) {
        SignalExecutors.BOUNDED.execute {
            Log.i(TAG, "Loading all scheduled export configurations.")
            val scheduler = ChatExportScheduler(context.applicationContext)
            val schedules = scheduler.getAllScheduledExportConfigs()
            scheduledExportsLiveData.postValue(schedules)
        }
    }

    fun cancelScheduledExport(context: Context, uniqueWorkName: String) {
        SignalExecutors.BOUNDED.execute {
            Log.i(TAG, "Cancelling scheduled export: $uniqueWorkName")
            val scheduler = ChatExportScheduler(context.applicationContext)
            scheduler.cancelScheduledExport(uniqueWorkName)
            // After cancellation, refresh the list
            loadAllScheduledExports(context)
            Log.i(TAG, "Scheduled export $uniqueWorkName cancelled and list refreshed.")
        }
    }
}
