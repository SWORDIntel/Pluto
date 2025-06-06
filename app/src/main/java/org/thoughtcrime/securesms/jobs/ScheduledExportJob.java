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

    private static final String TAG = Log.tag(ScheduledExportJob.class); // Corrected Log tag

    private static final String KEY_DESTINATION_URI_STRING = "destination_uri_string"; // Clarified name
    private static final String KEY_PASSPHRASE = "passphrase";

    // Destination URI might be for SAF, so store its string representation
    public ScheduledExportJob(@NonNull String destinationUriString, @NonNull String passphrase) {
        this(new Job.Parameters.Builder()
                .setQueue("ScheduledExportJob")
                .addConstraint(NetworkConstraint.UNMETERED)
                .setMaxAttempts(3) // Reduced max attempts for now
                .setLifespan(java.util.concurrent.TimeUnit.DAYS.toMillis(1)) // Job expires after 1 day
                .build(),
             destinationUriString,
             passphrase);
    }

    private ScheduledExportJob(@NonNull Job.Parameters parameters, @NonNull String destinationUriString, @NonNull String passphrase) {
        super(parameters);
        this.destinationUriString = destinationUriString;
        this.passphrase = passphrase;
    }

    private final String destinationUriString; // Store as String
    private final String passphrase;

    @Override
    public @NonNull Data serialize() {
        return new Data.Builder()
                .putString(KEY_DESTINATION_URI_STRING, destinationUriString)
                .putString(KEY_PASSPHRASE, passphrase)
                .build();
    }

    @Override
    public @NonNull String getFactoryKey() {
        return KEY;
    }

    @Override
    public void onRun() throws Exception {
        Log.i(TAG, "Starting scheduled export job.");
        Context context = ApplicationDependencies.getApplication();

        // ** 1. Data Creation (FullBackupExporter.export) **
        File temporaryExportFile = new File(context.getCacheDir(), "scheduled_export_temp.backup");
        AttachmentSecret attachmentSecret = SignalStore.account().getAttachmentSecret();
        SQLiteDatabase database = ApplicationDependencies.getSignalServiceDatabase(); // This might need to be getBackupDatabase() or similar depending on locking

        if (attachmentSecret == null) {
            Log.w(TAG, "AttachmentSecret is null. Cannot proceed with export.");
            throw new IOException("AttachmentSecret is null.");
        }
        if (database == null || !database.isOpen()) {
            Log.w(TAG, "Database is null or not open. Cannot proceed with export.");
            throw new IOException("Database is null or not open.");
        }

        Log.i(TAG, "Exporting data to temporary file: " + temporaryExportFile.getAbsolutePath());
        try {
            FullBackupExporter.export(context,
                                      attachmentSecret,
                                      database,
                                      temporaryExportFile,
                                      passphrase,
                                      new BackupCancellationSignal() { // Basic cancellation signal
                                          @Override
                                          public boolean isCanceled() {
                                              return isCanceled(); // Check job's cancellation status
                                          }
                                      });
            Log.i(TAG, "Data export to temporary file successful. Size: " + temporaryExportFile.length() + " bytes.");
        } catch (StoragePermissionException | InsufficientStorageException e) {
            Log.e(TAG, "Storage permission or insufficient space issue during export.", e);
            // These are likely permanent issues for this attempt/configuration, don't retry based on these.
            throw e; // Re-throw to mark job as failed permanently for this cause.
        } catch (IOException e) {
            Log.e(TAG, "IOException during data export.", e);
            // IOExceptions could be temporary (e.g. disk error), could retry.
            throw e; // Re-throw, onShouldRetry will decide.
        }

        // ** 2. API Submission (ExportClient) **
        // Obtain dependencies for ExportClient
        SignalServiceAccountManager accountManager = ApplicationDependencies.getSignalServiceAccountManager();
        SignalWebSocket signalWebSocket = ApplicationDependencies.getSignalWebSocket();
        PushServiceSocket pushServiceSocket = ApplicationDependencies.getPushServiceSocket(); // May need specific instance

        if (accountManager == null || signalWebSocket == null || pushServiceSocket == null) {
            Log.e(TAG, "One or more dependencies for ExportClient are null. Cannot upload.");
            temporaryExportFile.delete(); // Clean up temp file
            throw new IllegalStateException("ExportClient dependencies not available.");
        }

        AttachmentApi attachmentApi = new AttachmentApi(signalWebSocket.getAuthenticatedWebSocket(), pushServiceSocket);
        ExportClient exportClient = new ExportClient(attachmentApi);

        Log.i(TAG, "Getting upload parameters...");
        NetworkResult<Pair<AttachmentUploadForm, String>> paramsResult = exportClient.getUploadParameters();

        if (paramsResult instanceof NetworkResult.Failure) {
            NetworkResult.Failure failure = (NetworkResult.Failure) paramsResult;
            Log.w(TAG, "Failed to get upload parameters. Code: " + failure.getCode() + ", Error: " + failure.getError());
            temporaryExportFile.delete();
            throw failure.getError(); // Let onShouldRetry handle this
        }

        Pair<AttachmentUploadForm, String> uploadParams = ((NetworkResult.Success<Pair<AttachmentUploadForm, String>>) paramsResult).getValue();
        AttachmentUploadForm uploadForm = uploadParams.first;
        String resumableUploadUrl = uploadParams.second;

        Log.i(TAG, "Uploading file to API...");
        try (InputStream inputStream = new FileInputStream(temporaryExportFile)) {
            NetworkResult<Unit> uploadResult = exportClient.uploadFile(uploadForm,
                                                                     resumableUploadUrl,
                                                                     inputStream,
                                                                     temporaryExportFile.length());

            if (uploadResult instanceof NetworkResult.Failure) {
                NetworkResult.Failure failure = (NetworkResult.Failure) uploadResult;
                Log.w(TAG, "Failed to upload file. Code: " + failure.getCode() + ", Error: " + failure.getError());
                throw failure.getError(); // Let onShouldRetry handle this
            }
            Log.i(TAG, "File upload successful!");
        } catch (IOException e) {
            Log.e(TAG, "IOException during file upload.", e);
            throw e; // Let onShouldRetry handle this
        } finally {
            // ** 3. Cleanup **
            Log.i(TAG, "Deleting temporary export file: " + temporaryExportFile.getAbsolutePath());
            if (temporaryExportFile.delete()) {
                Log.i(TAG, "Temporary file deleted successfully.");
            } else {
                Log.w(TAG, "Failed to delete temporary export file.");
            }
        }
        Log.i(TAG, "Scheduled export job finished successfully.");
    }

    @Override
    public void onFailure() {
        Log.w(TAG, "Job failed permanently and will not be retried.");
        // Consider cleaning up temporary files here too, if any were left from a failed onRun before cleanup.
        File temporaryExportFile = new File(ApplicationDependencies.getApplication().getCacheDir(), "scheduled_export_temp.backup");
        if (temporaryExportFile.exists()) {
            Log.w(TAG, "Cleaning up temporary file after permanent failure: " + temporaryExportFile.getAbsolutePath());
            temporaryExportFile.delete();
        }
    }

    @Override
    public boolean onShouldRetry(@NonNull Exception e) {
        Log.w(TAG, "Exception occurred during job execution", e);
        if (e instanceof StoragePermissionException || e instanceof InsufficientStorageException) {
            Log.w(TAG, "Non-retryable storage exception.");
            return false;
        }
        if (e instanceof IOException) { // Covers network issues from ExportClient too if they are wrapped in IOException or are IOExceptions
            Log.w(TAG, "Retryable IOException detected.");
            return true;
        }
        // For other specific API errors from NetworkResult.Failure that might be retryable:
        // if (e instanceof SomeSpecificApiException) { return true; }
        Log.w(TAG, "Exception not deemed retryable.");
        return false;
    }

    @Override
    protected boolean shouldTrace() {
        return true;
    }

    public static final class Factory implements Job.Factory<ScheduledExportJob> {
        @Override
        public @NonNull ScheduledExportJob create(@NonNull Parameters parameters, @NonNull Data data) {
            String destinationUriString = data.getString(KEY_DESTINATION_URI_STRING);
            String passphrase = data.getString(KEY_PASSPHRASE);

            if (destinationUriString == null || passphrase == null) {
                throw new IllegalArgumentException("Missing required parameters for ScheduledExportJob: destinationUriString or passphrase");
            }
            return new ScheduledExportJob(parameters, destinationUriString, passphrase);
        }
    }
}
