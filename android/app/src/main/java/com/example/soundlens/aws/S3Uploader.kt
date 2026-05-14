package com.example.soundlens.aws

import android.content.Context
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

object S3Uploader {

    private fun s3Client(): AmazonS3Client {
        val creds = BasicAWSCredentials(AwsConfig.ACCESS_KEY, AwsConfig.SECRET_KEY)
        return AmazonS3Client(creds).apply {
            setRegion(com.amazonaws.regions.Region.getRegion(AwsConfig.REGION))
        }
    }

    private fun transferUtility(context: Context): TransferUtility {
        return TransferUtility.builder()
            .context(context.applicationContext)
            .s3Client(s3Client())
            .build()
    }

    /**
     * Sube el archivo y regresa el key que quedó en S3.
     */
    suspend fun uploadAudio(context: Context, file: File): String =
        suspendCoroutine { cont ->
            val key = AwsConfig.UPLOAD_PREFIX + UUID.randomUUID().toString() + "_" + file.name
            val tu = transferUtility(context)
            val uploadObserver = tu.upload(AwsConfig.BUCKET, key, file)

            uploadObserver.setTransferListener(object : TransferListener {
                override fun onStateChanged(id: Int, state: TransferState?) {
                    if (state == TransferState.COMPLETED) {
                        cont.resume(key)
                    } else if (state == TransferState.FAILED || state == TransferState.CANCELED) {
                        cont.resumeWithException(RuntimeException("S3 upload failed: $state"))
                    }
                }

                override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {}
                override fun onError(id: Int, ex: Exception?) {
                    cont.resumeWithException(ex ?: RuntimeException("S3 upload error"))
                }
            })
        }
}
