package com.example.soundlens.data

import android.content.Context
import com.example.soundlens.aws.LambdaInvoker

object IdentifyRepository {
    suspend fun identifyFromS3(
        context: Context,
        functionName: String,
        bucket: String,
        key: String,
        requestId: String
    ): String {
        return LambdaInvoker.identifyFromS3(
            context = context,
            functionName = functionName,
            bucket = bucket,
            key = key,
            requestId = requestId
        )
    }
}
