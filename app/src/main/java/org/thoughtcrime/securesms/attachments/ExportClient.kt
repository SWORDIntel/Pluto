package org.thoughtcrime.securesms.attachments

import org.whispersystems.signalservice.api.NetworkResult
import org.whispersystems.signalservice.api.attachment.AttachmentApi
import org.whispersystems.signalservice.internal.push.AttachmentUploadForm
import java.io.InputStream
import java.io.IOException

class ExportClient(private val attachmentApi: AttachmentApi) {

    fun getUploadParameters(): NetworkResult<Pair<AttachmentUploadForm, String>> {
        return try {
            val uploadFormResult = attachmentApi.getAttachmentV4UploadForm()
            if (uploadFormResult is NetworkResult.Failure) {
                return NetworkResult.Failure(uploadFormResult.error, uploadFormResult.code, uploadFormResult.response)
            }

            val uploadForm = (uploadFormResult as NetworkResult.Success).value
            val resumableUrlResult = attachmentApi.getResumableUploadUrl(uploadForm)

            resumableUrlResult.map { resumableUrl ->
                Pair(uploadForm, resumableUrl)
            }
        } catch (e: Exception) {
            NetworkResult.Failure(e, -1, null)
        }
    }

    fun uploadFile(
        uploadForm: AttachmentUploadForm,
        resumableUploadUrl: String,
        inputStream: InputStream,
        inputStreamLength: Long
    ): NetworkResult<Unit> {
        return try {
            attachmentApi.uploadPreEncryptedFileToAttachmentV4(
                uploadForm,
                resumableUploadUrl,
                inputStream,
                inputStreamLength
            )
        } catch (e: IOException) {
            NetworkResult.Failure(e, -1, null)
        } catch (e: Exception) {
            NetworkResult.Failure(e, -1, null)
        }
    }
}
