# Manual Test Plan: Chat Export Feature

## I. Prerequisites

1.  **Debug Build:** Ensure a debug build of the application is installed on a test device or emulator.
2.  **ADB Access:** Android Debug Bridge (adb) must be set up and accessible to trigger debug broadcasts and view logs (`adb logcat`).
3.  **Test Thread:** Have at least one chat thread with a few messages (e.g., threadId `1L` as used in `DebugExportTriggerReceiver`).
4.  **Network Access:** For API export tests, ensure the device has an active internet connection.
5.  **Logcat Monitoring:** Be prepared to monitor logcat for specific tags and messages. Key tags include:
    *   `DebugExportTriggerReceiver`
    *   `ExportViewModel`
    *   `ChatExportScheduler`
    *   `ScheduledExportJob`
    *   `ExportClient`
    *   `FullBackupExporter`
6.  **Passphrase:** The debug passphrase used is "debug_export_passphrase".
7.  **API URL:** The debug API URL used is "https://debug.example.com/upload". For TC1 and TC7, a mock server or a service like [Beeceptor](https://beeceptor.com/) or [RequestBin.com](https://requestbin.com/) should be set up at this URL to inspect incoming requests or simulate upload failures.

## II. Test Cases

---

### TC1: One-Time Export (API Destination) - Success

*   **Objective:** Verify that a one-time export to an API endpoint can be triggered and completes successfully.
*   **Preconditions:**
    *   Prerequisites (1-7) met.
    *   A mock API endpoint is set up at `https://debug.example.com/upload` to receive file uploads and return a success (e.g., HTTP 200).
*   **Steps:**
    1.  Open a terminal and run `adb logcat -s DebugExportTriggerReceiver ExportViewModel ScheduledExportJob ExportClient FullBackupExporter ChatExportScheduler`.
    2.  Send the debug broadcast to trigger a one-time export:
        ```bash
        adb shell am broadcast -a org.thoughtcrime.securesms.DEBUG_TRIGGER_EXPORT --es export_type "onetime"
        ```
    3.  Monitor logcat.
    4.  Check the mock API endpoint for the received export file.
*   **Expected Results:**
    1.  **Logcat:**
        *   `DebugExportTriggerReceiver`: "Debug export trigger received", "Initiating ONE-TIME debug export..."
        *   `ExportViewModel`: "startExportOrSchedule called...", "Enqueueing ScheduledExportJob for a one-time export..."
        *   `ScheduledExportJob`: "Starting export job.", "Job parameters: threadId=1...", "Beginning export to temporary file...", "Data export to temporary file successful. Size: ... bytes.", "Getting upload parameters...", "Successfully got upload parameters...", "Starting file upload to API...", "File upload successful for thread 1!", "Deleting temporary export file...", "Temporary file for thread 1 deleted successfully.", "Export job for thread 1 finished successfully."
        *   `ExportClient`: "getUploadParameters called.", "Successfully retrieved upload parameters...", "uploadFile called.", "uploadFile successful for form key: ..."
        *   `FullBackupExporter`: Logs indicating export process (e.g., "internalExport started", "Writing database version", "Exporting table: ...", "Finalizing backup stream.").
    2.  **Mock API:** The export file (e.g., `export_1_xxxxxxxx.backup`, likely JSON format) should be received at the mock endpoint.
    3.  **Application:** No crashes or ANRs.

---

### TC2: Scheduled Export (API Destination) - Creation

*   **Objective:** Verify that a scheduled export (e.g., daily) to an API endpoint can be successfully created.
*   **Preconditions:**
    *   Prerequisites (1-7) met.
*   **Steps:**
    1.  Open a terminal and run `adb logcat -s DebugExportTriggerReceiver ExportViewModel ChatExportScheduler`.
    2.  Send the debug broadcast to trigger a scheduled export:
        ```bash
        adb shell am broadcast -a org.thoughtcrime.securesms.DEBUG_TRIGGER_EXPORT --es export_type "scheduled"
        ```
    3.  Monitor logcat.
    4.  (Optional) Check app's internal storage (e.g., KeyValueDatabase via debug options if available, or device file explorer for debuggable app's data dir) for evidence of the persisted schedule data.
*   **Expected Results:**
    1.  **Logcat:**
        *   `DebugExportTriggerReceiver`: "Debug export trigger received", "Initiating SCHEDULED debug export..."
        *   `ExportViewModel`: "startExportOrSchedule called...", "Scheduling export for threadId: 1 with frequency: DAILY"
        *   `ChatExportScheduler`: "scheduleExportFromOptions called...", "scheduleExport called. threadId: 1...", "Scheduled export for thread 'DebugScheduledChat_1' (1) with work name 'scheduled_export_thread_1', frequency: DAILY", "Saved schedule config for scheduled_export_thread_1".
    2.  **Application:** No crashes. The Manage Scheduled Exports screen (if navigated to) should show the newly scheduled export.

---

### TC3: Scheduled Export - Execution (Simulated/Verified)

*   **Objective:** Verify that a previously scheduled export executes correctly. (Exact timing might be hard to test directly, so this often involves checking WorkManager's status or forcing job execution via debug tools if possible).
*   **Preconditions:**
    *   TC2 completed successfully. A scheduled export for threadId `1L` exists.
    *   Prerequisites (1-7) met.
    *   Mock API endpoint from TC1 is active.
    *   Device meets job constraints (e.g., unmetered network, charging - depending on `ScheduledExportJob` and `ChatExportScheduler` constraints).
*   **Steps:**
    1.  Open a terminal and run `adb logcat -s ScheduledChatExportWorker ScheduledExportJob ExportClient FullBackupExporter ChatExportScheduler WorkManager`. (Note: `ScheduledChatExportWorker` is the actual worker used by `ChatExportScheduler`).
    2.  Either wait for the scheduled time (if feasible, e.g., if set to a short interval for testing) OR use `adb shell cmd jobscheduler run -f org.thoughtcrime.securesms <JOB_ID>` if the job is visible to jobscheduler and its ID can be found, OR use WorkManager testing tools if available in debug environment to force run `scheduled_export_thread_1`.
        *   *Note:* Forcing WorkManager jobs can be complex. An alternative is to check WorkManager logs for successful execution after the interval.
    3.  Monitor logcat for job execution.
    4.  Check the mock API endpoint for the received export file.
*   **Expected Results:**
    1.  **Logcat (when job runs):**
        *   `WorkManager`: Logs indicating it's starting `ScheduledChatExportWorker` for `scheduled_export_thread_1`.
        *   `ScheduledChatExportWorker`: Logs indicating it's starting, parameters received.
        *   `ScheduledExportJob` (if `ScheduledChatExportWorker` delegates to its logic or similar logging is implemented in the worker): "Starting export job.", "Job parameters: threadId=1...", and subsequent logs similar to TC1's `ScheduledExportJob` logs.
        *   `ExportClient` and `FullBackupExporter`: Logs similar to TC1.
    2.  **Mock API:** The export file should be received.
    3.  **Application:** No crashes. The schedule should remain active for the next run (e.g., visible in Manage Scheduled Exports screen).

---

### TC4: Scheduled Export - Cancellation

*   **Objective:** Verify that a scheduled export can be cancelled.
*   **Preconditions:**
    *   TC2 completed successfully. A scheduled export for threadId `1L` (work name `scheduled_export_thread_1`) exists.
    *   Prerequisites (1-7) met.
*   **Steps:**
    1.  Open the "Manage Scheduled Exports" screen in the app (manual navigation).
    2.  Locate the scheduled export for "DebugScheduledChat_1".
    3.  Tap the "Cancel" button for this export.
    4.  Monitor logcat (`adb logcat -s ManageScheduledExportsViewModel ChatExportScheduler`).
*   **Expected Results:**
    1.  **Logcat:**
        *   `ManageScheduledExportsViewModel`: "Cancelling scheduled export: scheduled_export_thread_1", "Scheduled export scheduled_export_thread_1 cancelled and list refreshed."
        *   `ChatExportScheduler`: "Attempting to cancel scheduled export with work name 'scheduled_export_thread_1'", "Removed schedule config for scheduled_export_thread_1", "Cancellation request processed for work name 'scheduled_export_thread_1'".
    2.  **Application:** The "Manage Scheduled Exports" screen should update, and the cancelled export should no longer be listed.
    3.  Subsequent checks should confirm the job (e.g., `scheduled_export_thread_1`) no longer runs.

---

### TC5: Error Handling - Insufficient Storage (One-Time Export)

*   **Objective:** Verify that insufficient storage space is handled gracefully during a one-time export.
*   **Preconditions:**
    *   Prerequisites (1-7) met.
    *   Test device has very little free storage space (less than the estimated size of an export, e.g., < 200MB or less than DB size). This might require manually filling up storage.
*   **Steps:**
    1.  Open a terminal and run `adb logcat -s DebugExportTriggerReceiver ExportViewModel ScheduledExportJob FullBackupExporter`.
    2.  Send the debug broadcast for a one-time export:
        ```bash
        adb shell am broadcast -a org.thoughtcrime.securesms.DEBUG_TRIGGER_EXPORT --es export_type "onetime"
        ```
    3.  Monitor logcat.
*   **Expected Results:**
    1.  **Logcat:**
        *   `FullBackupExporter`: Logs indicating storage check, e.g., "Available space in /data/user/0/org.thoughtcrime.securesms/cache: ...MB. Estimated required: ...MB." followed by an error if it throws `InsufficientStorageException`.
        *   `ScheduledExportJob`: "Starting export job.", "Job parameters: threadId=1...", "Beginning export to temporary file...", followed by "Storage permission or insufficient space issue during export for thread 1." and the stack trace of `InsufficientStorageException`.
        *   `ScheduledExportJob`: "Job failed permanently and will not be retried."
    2.  **Application:** No crash. The export should fail, and this failure should be logged. No file should be uploaded to the API.

---

### TC6: Error Handling - No Storage Permission (One-Time Export, if applicable for Android < Q)

*   **Objective:** Verify that lack of storage permission is handled gracefully (primarily for Android versions below Q where `WRITE_EXTERNAL_STORAGE` is more relevant for `File` access outside app-specific directories, though `FullBackupExporter` writes to cache which usually doesn't need this. This TC is more relevant if the output path were to an external shared directory).
*   **Preconditions:**
    *   Prerequisites (1-7) met.
    *   Test device is Android 9 (Pie) or older.
    *   The app's `WRITE_EXTERNAL_STORAGE` permission has been manually revoked via app settings.
    *   *Self-correction:* `FullBackupExporter` writes to `context.getCacheDir()`. This directory does not require `WRITE_EXTERNAL_STORAGE` permission. Therefore, this specific test case might not be triggerable with the current implementation path. If the export path were configurable to external storage, this would be more relevant. For now, this TC might be **Not Applicable (NA)** unless `FullBackupExporter`'s output path strategy changes.
*   **Steps:**
    1.  (If applicable) Revoke `WRITE_EXTERNAL_STORAGE` permission.
    2.  Open a terminal and run `adb logcat -s DebugExportTriggerReceiver ExportViewModel ScheduledExportJob FullBackupExporter`.
    3.  Send the debug broadcast for a one-time export.
    4.  Monitor logcat.
*   **Expected Results (If Applicable):**
    1.  **Logcat:**
        *   `FullBackupExporter`: If permission check fails, "WRITE_EXTERNAL_STORAGE permission not granted."
        *   `ScheduledExportJob`: "Storage permission or insufficient space issue..." and stack trace of `StoragePermissionException`.
        *   `ScheduledExportJob`: "Job failed permanently and will not be retried."
    2.  **Application:** No crash. Export fails.

---

### TC7: Error Handling - API Upload Failure (One-Time Export)

*   **Objective:** Verify that an API upload failure during a one-time export is handled and potentially retried (or fails gracefully after attempts).
*   **Preconditions:**
    *   Prerequisites (1-7) met.
    *   Mock API endpoint at `https://debug.example.com/upload` is configured to return an error (e.g., HTTP 500, 403, or simulate network timeout).
*   **Steps:**
    1.  Open a terminal and run `adb logcat -s DebugExportTriggerReceiver ExportViewModel ScheduledExportJob ExportClient FullBackupExporter`.
    2.  Send the debug broadcast for a one-time export:
        ```bash
        adb shell am broadcast -a org.thoughtcrime.securesms.DEBUG_TRIGGER_EXPORT --es export_type "onetime"
        ```
    3.  Monitor logcat for export, upload attempt, failure, and any retry attempts.
*   **Expected Results:**
    1.  **Logcat:**
        *   `ScheduledExportJob`: Initial export steps succeed ("Data export to temporary file successful...").
        *   `ExportClient`: "uploadFile called...", then "uploadFile failed for form key: ... Code: <error_code>, Error: <error_message>".
        *   `ScheduledExportJob`: "Exception occurred during job execution for thread 1..." (showing the error from `ExportClient`).
        *   `ScheduledExportJob`: "Retryable IOException detected for thread 1." (if the error was mapped to IOException or a retryable `NetworkResult` error).
        *   Depending on `setMaxAttempts` (currently 3 in `ScheduledExportJob`), the job might retry a few times. Eventually, if it continues to fail: "Job failed permanently and will not be retried."
        *   `ScheduledExportJob`: "Deleting temporary export file..." logs should appear after the final attempt (success or failure).
    2.  **Mock API:** Should show incoming requests, but no successful upload persisted if it's configured to always fail.
    3.  **Application:** No crash. The export should ultimately fail after exhausting retries.

---

This test plan provides a good starting point for manually verifying the core chat export functionality.
