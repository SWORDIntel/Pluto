package org.thoughtcrime.securesms.jobs;

import androidx.annotation.NonNull;

import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.JobLogger;
import org.thoughtcrime.securesms.jobmanager.Constraint;
import org.thoughtcrime.securesms.logging.Log; // Assuming this is the correct Log import

import android.content.Context;
import android.util.Pair; // Added missing import for Pair
import androidx.documentfile.provider.DocumentFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.thoughtcrime.securesms.attachments.ExportClient;
import org.thoughtcrime.securesms.backup.FullBackupExporter;
import org.thoughtcrime.securesms.backup.BackupCancellationSignal;
import org.thoughtcrime.securesms.backup.StoragePermissionException;
import org.thoughtcrime.securesms.backup.InsufficientStorageException;
import org.thoughtcrime.securesms.crypto.AttachmentSecret;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.SignalWebSocket;
import org.whispersystems.signalservice.api.attachment.AttachmentApi;
import org.whispersystems.signalservice.api.NetworkResult;
import org.whispersystems.signalservice.internal.push.AttachmentUploadForm;
import org.whispersystems.signalservice.internal.push.PushServiceSocket;


public class ScheduledExportJob extends BaseJob {

    public static final String KEY = "ScheduledExportJob";

    private static final String TAG = Log.tag(ScheduledExportJob.class);

    // Keys for Job Data
    public static final String KEY_THREAD_ID = "thread_id";
    public static final String KEY_PASSPHRASE = "passphrase";
    public static final String KEY_EXPORT_FORMAT = "export_format"; // e.g., ExportFormat.JSON.name()
    public static final String KEY_EXPORT_DESTINATION_TYPE = "export_destination_type"; // e.g., ExportDestination.API_ENDPOINT.name()
    public static final String KEY_API_URL = "api_url"; // Nullable
    // KEY_DESTINATION_URI_STRING (for temp file) is removed, will be generated in onRun

    public ScheduledExportJob(long threadId, @NonNull String passphrase,
                              @NonNull String exportFormatName, @NonNull String exportDestinationTypeName,
                              @Nullable String apiUrl) {
        super(new Job.Parameters.Builder()
                .setQueue("ScheduledExportJob")
                .addConstraint(NetworkConstraint.UNMETERED) // Default, can be overridden by specific scheduling if needed
                .setMaxAttempts(3)
                .setLifespan(java.util.concurrent.TimeUnit.DAYS.toMillis(1))
                .setInputData(new Data.Builder()
                        .putLong(KEY_THREAD_ID, threadId)
                        .putString(KEY_PASSPHRASE, passphrase)
                        .putString(KEY_EXPORT_FORMAT, exportFormatName)
                        .putString(KEY_EXPORT_DESTINATION_TYPE, exportDestinationTypeName)
                        .putString(KEY_API_URL, apiUrl) // putString handles null correctly
                        .build())
                .build());
    }

    // Private constructor for Factory
    private ScheduledExportJob(@NonNull Job.Parameters parameters) {
        super(parameters);
    }

    // No need to override serialize() if all state is in Parameters' Data.
    // BaseJob.serialize() handles it.

    @Override
    public @NonNull String getFactoryKey() {
        return KEY;
    }

    @Override
    public void onRun() throws Exception {
        Log.i(TAG, "Starting export job.");
        Context context = ApplicationDependencies.getApplication();
        Data data = getParameters().getInputData();

        long threadId = data.getLong(KEY_THREAD_ID, -1L);
        String passphrase = data.getString(KEY_PASSPHRASE);
        String exportFormatName = data.getString(KEY_EXPORT_FORMAT);
        String exportDestinationTypeName = data.getString(KEY_EXPORT_DESTINATION_TYPE);
        String apiUrl = data.getString(KEY_API_URL); // Will be null if not set

        if (threadId == -1L || passphrase == null || exportFormatName == null || exportDestinationTypeName == null) {
            Log.e(TAG, "Missing critical parameters for export job. ThreadId: $threadId, Passphrase provided: ${passphrase != null}, Format: $exportFormatName, DestType: $exportDestinationTypeName");
            throw new IllegalArgumentException("Missing critical parameters for export job.");
        }

        Log.i(TAG, "Job parameters: threadId=$threadId, format=$exportFormatName, destinationType=$exportDestinationTypeName, apiUrl=${apiUrl ?: "N/A"}");

        // Generate temp file path inside onRun
        File temporaryExportFile = new File(context.getCacheDir(), "export_${threadId}_${System.currentTimeMillis()}.backup");

        AttachmentSecret attachmentSecret = SignalStore.account().getAttachmentSecret();
        SQLiteDatabase database = ApplicationDependencies.getSignalServiceDatabase();

        if (attachmentSecret == null) {
            Log.w(TAG, "AttachmentSecret is null for thread $threadId. Cannot proceed with export.");
            throw new IOException("AttachmentSecret is null for thread " + threadId);
        }
        if (database == null || !database.isOpen()) {
            Log.w(TAG, "Database is null or not open for thread $threadId. Cannot proceed with export.");
            throw new IOException("Database is null or not open for thread " + threadId);
        }

        Log.i(TAG, "Beginning export to temporary file for thread $threadId: " + temporaryExportFile.getAbsolutePath());
        try {
            FullBackupExporter.export(context,
                                      attachmentSecret,
                                      database,
                                      temporaryExportFile,
                                      passphrase, // Retrieved from Data
                                      new BackupCancellationSignal() {
                                          @Override
                                          public boolean isCanceled() {
                                              return ScheduledExportJob.this.isCanceled(); // Check job's cancellation status
                                          }
                                      });
            Log.i(TAG, "Data export to temporary file successful. Size: " + temporaryExportFile.length() + " bytes.");
        } catch (StoragePermissionException | InsufficientStorageException e) {
            Log.e(TAG, "Storage permission or insufficient space issue during export for thread $threadId.", e);
            throw e;
        } catch (IOException e) {
            Log.e(TAG, "IOException during data export.", e);
            // IOExceptions could be temporary (e.g. disk error), could retry.
            throw e; // Re-throw, onShouldRetry will decide.
        }

        // ** 2. API Submission (ExportClient) **
        // Obtain dependencies for ExportClient
        SignalServiceAccountManager accountManager = ApplicationDependencies.getSignalServiceAccountManager();
        SignalWebSocket signalWebSocket = ApplicationDependencies.getSignalWebSocket();
        PushServiceSocket pushServiceSocket = ApplicationDependencies.getPushServiceSocket();

        if (accountManager == null || signalWebSocket == null || pushServiceSocket == null) {
            Log.e(TAG, "One or more dependencies for ExportClient are null for thread $threadId. Cannot upload.");
            temporaryExportFile.delete();
            throw new IllegalStateException("ExportClient dependencies not available for thread $threadId.");
        }

        // TODO: Check exportDestinationTypeName. If it's API_ENDPOINT, then proceed with upload.
        //       If it's LOCAL_FILE, the work is done (file is in temporaryExportFile).
        //       The final destination for LOCAL_FILE would need to be handled, perhaps by copying
        //       this temp file to a user-specified SAF URI, which is complex for a background job.
        //       For now, assume API_ENDPOINT is the primary target for this job's upload phase.
        //       If local, the temp file is just created and then cleaned up. This might need refinement
        //       based on actual feature requirements for "local" one-time exports via this job.

        if (exportDestinationTypeName.equals("API_ENDPOINT")) {
            AttachmentApi attachmentApi = new AttachmentApi(signalWebSocket.getAuthenticatedWebSocket(), pushServiceSocket);
            ExportClient exportClient = new ExportClient(attachmentApi);

            Log.i(TAG, "Getting upload parameters for thread $threadId...");
            NetworkResult<Pair<AttachmentUploadForm, String>> paramsResult = exportClient.getUploadParameters();

            if (paramsResult instanceof NetworkResult.Failure) {
                NetworkResult.Failure failureResult = (NetworkResult.Failure) paramsResult;
                Log.w(TAG, "Failed to get upload parameters for thread $threadId. Code: " + failureResult.getCode() + ", Error: " + failureResult.getError().getMessage(), failureResult.getError());
                temporaryExportFile.delete();
                throw failureResult.getError();
            }

            Pair<AttachmentUploadForm, String> uploadParams = ((NetworkResult.Success<Pair<AttachmentUploadForm, String>>) paramsResult).getValue();
            AttachmentUploadForm uploadForm = uploadParams.first;
            String resumableUploadUrl = uploadParams.second;
            Log.i(TAG, "Successfully got upload parameters for thread $threadId. CDN: ${uploadForm.getCdnNumber()}, Key: ${uploadForm.getKey()}, URL starts with: ${resumableUploadUrl.substring(0, Math.min(resumableUploadUrl.length(), 30))}");

            Log.i(TAG, "Starting file upload to API for thread $threadId. File size: " + temporaryExportFile.length() + " bytes.");
            try (InputStream inputStream = new FileInputStream(temporaryExportFile)) {
                NetworkResult<Unit> uploadResult = exportClient.uploadFile(uploadForm,
                                                                         resumableUploadUrl,
                                                                         inputStream,
                                                                         temporaryExportFile.length());

                if (uploadResult instanceof NetworkResult.Failure) {
                    NetworkResult.Failure failureResult = (NetworkResult.Failure) uploadResult;
                    Log.w(TAG, "Failed to upload file for thread $threadId. Code: " + failureResult.getCode() + ", Error: " + failureResult.getError().getMessage(), failureResult.getError());
                    throw failureResult.getError();
                }
                Log.i(TAG, "File upload successful for thread $threadId!");
            } catch (IOException e) {
                Log.e(TAG, "IOException during file upload for thread $threadId.", e);
                throw e;
            }
        } else {
            Log.i(TAG, "Export destination is local for thread $threadId. Temporary file created at: " + temporaryExportFile.getAbsolutePath());
            // If it's a local export, the temporary file is the result.
            // It will be cleaned up by the finally block. For a true local export,
            // this file would need to be moved to a user-accessible location,
            // which is outside the scope of this job's current upload logic.
        }

        // Cleanup is now outside the try-catch for upload, happens for both API and Local (temp file)
        // ** 3. Cleanup **
        Log.i(TAG, "Deleting temporary export file for thread $threadId: " + temporaryExportFile.getAbsolutePath());
        if (temporaryExportFile.delete()) {
            Log.i(TAG, "Temporary file for thread $threadId deleted successfully.");
        } else {
            Log.w(TAG, "Failed to delete temporary export file for thread $threadId.");
        }

        Log.i(TAG, "Export job for thread $threadId finished successfully.");
    }

    @Override
    public void onFailure() {
        Log.w(TAG, "Job failed permanently and will not be retried. InputData: " + getParameters().getInputData().toString());
        // Attempt to clean up temp file if path can be reconstructed or was stored
        // For now, no specific path reconstruction here, relies on onRun's finally block if it reached that far.
    }

    @Override
    public boolean onShouldRetry(@NonNull Exception e) {
        Data data = getParameters().getInputData();
        long threadId = data.getLong(KEY_THREAD_ID, -1L);
        Log.w(TAG, "Exception occurred during job execution for thread $threadId", e);

        if (e instanceof StoragePermissionException || e instanceof InsufficientStorageException) {
            Log.w(TAG, "Non-retryable storage exception for thread $threadId.");
            return false;
        }
        if (e instanceof IOException) {
            Log.w(TAG, "Retryable IOException detected for thread $threadId.");
            return true;
        }
        Log.w(TAG, "Exception not deemed retryable for thread $threadId.");
        return false;
    }

    @Override
    protected boolean shouldTrace() {
        return true;
    }

    public static final class Factory implements Job.Factory<ScheduledExportJob> {
        @Override
        public @NonNull ScheduledExportJob create(@NonNull Parameters parameters, @NonNull Data data) {
            // Data is already part of parameters, just pass parameters to the private constructor.
            return new ScheduledExportJob(parameters);
        }
    }
}
