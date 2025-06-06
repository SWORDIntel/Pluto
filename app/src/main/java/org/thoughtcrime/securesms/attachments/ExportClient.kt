package org.thoughtcrime.securesms.attachments

import org.whispersystems.signalservice.api.NetworkResult
import org.whispersystems.signalservice.api.attachment.AttachmentApi
import org.whispersystems.signalservice.internal.push.AttachmentUploadForm
import java.io.InputStream
import java.io.IOException
import org.thoughtcrime.securesms.logging.Log // Assuming this is the correct Log import

class ExportClient(private val attachmentApi: AttachmentApi) {

    companion object {
        private val TAG = Log.tag(ExportClient::class.java)
    }

    fun getUploadParameters(): NetworkResult<Pair<AttachmentUploadForm, String>> {
        Log.i(TAG, "getUploadParameters called.")
        return try {
            val uploadFormResult = attachmentApi.getAttachmentV4UploadForm()
            if (uploadFormResult is NetworkResult.Failure) {
                Log.w(TAG, "Failed to get attachment V4 upload form. Code: ${uploadFormResult.code}, Error: ${uploadFormResult.error.message}", uploadFormResult.error)
                return NetworkResult.Failure(uploadFormResult.error, uploadFormResult.code, uploadFormResult.response)
            }

            val uploadForm = (uploadFormResult as NetworkResult.Success).value
            Log.d(TAG, "Got V4 upload form. CDN: ${uploadForm.cdnNumber}, Key: ${uploadForm.key}")

            val resumableUrlResult = attachmentApi.getResumableUploadUrl(uploadForm)
            if (resumableUrlResult is NetworkResult.Failure) {
                Log.w(TAG, "Failed to get resumable upload URL. Code: ${resumableUrlResult.code}, Error: ${resumableUrlResult.error.message}", resumableUrlResult.error)
                return NetworkResult.Failure(resumableUrlResult.error, resumableUrlResult.code, resumableUrlResult.response)
            }

            val finalResult = resumableUrlResult.map { resumableUrl ->
                Log.i(TAG, "Successfully retrieved upload parameters. URL starts with: ${resumableUrl.substring(0, Math.min(resumableUrl.length, 30))}")
                Pair(uploadForm, resumableUrl)
            }
            finalResult
        } catch (e: Exception) {
            Log.e(TAG, "Exception in getUploadParameters.", e)
            NetworkResult.Failure(e, -1, null)
        }
    }

    fun uploadFile(
        uploadForm: AttachmentUploadForm,
        resumableUploadUrl: String,
        inputStream: InputStream,
        inputStreamLength: Long
    ): NetworkResult<Unit> {
        Log.i(TAG, "uploadFile called. Form Key: ${uploadForm.key}, URL starts with: ${resumableUploadUrl.substring(0, Math.min(resumableUploadUrl.length, 30))}, File length: $inputStreamLength bytes.")
        return try {
            val uploadResult = attachmentApi.uploadPreEncryptedFileToAttachmentV4(
                uploadForm,
                resumableUploadUrl,
                inputStream,
                inputStreamLength
            )

            if (uploadResult is NetworkResult.Success) {
                Log.i(TAG, "uploadFile successful for form key: ${uploadForm.key}.")
            } else if (uploadResult is NetworkResult.Failure) {
                Log.w(TAG, "uploadFile failed for form key: ${uploadForm.key}. Code: ${uploadResult.code}, Error: ${uploadResult.error.message}", uploadResult.error)
            }
            uploadResult
        } catch (e: IOException) {
            Log.e(TAG, "IOException in uploadFile for form key: ${uploadForm.key}.", e)
            NetworkResult.Failure(e, -1, null)
        } catch (e: Exception) {
            Log.e(TAG, "Generic exception in uploadFile for form key: ${uploadForm.key}.", e)
            NetworkResult.Failure(e, -1, null)
        }
    }
}
