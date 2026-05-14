package com.example.soundlens.aws

import android.content.Context
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.lambda.AWSLambdaClient
import com.amazonaws.services.lambda.model.InvokeRequest
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

object LambdaInvoker {

    /** Convierte AwsConfig.REGION a formato válido: "us-west-2" */
    private fun normalizeRegion(): Regions {
        // Lo que tengas (US_WEST_2, us-west-2, etc.)
        val raw = AwsConfig.REGION.toString()

        // Convertimos cualquier formato raro
        val good = raw
            .lowercase()          // us_west_2 / us-west-2
            .replace("_", "-")    // us-west-2
            .replace(" ", "")     // por si acaso

        return Regions.fromName(good) // ← ahora sí funciona
    }

    fun warmup(context: Context, functionName: String = "shazam-indexer") {
        try {
            val creds = BasicAWSCredentials(AwsConfig.ACCESS_KEY, AwsConfig.SECRET_KEY)
            val regionEnum = normalizeRegion()
            val client = AWSLambdaClient(creds).apply {
                setRegion(Region.getRegion(regionEnum))
            }

            val payload = JSONObject().put("ping", true).toString()

            val req = InvokeRequest()
                .withFunctionName(functionName)
                .withPayload(ByteBuffer.wrap(payload.toByteArray(StandardCharsets.UTF_8)))

            client.invoke(req)
        } catch (_: Exception) { }
    }

    fun identifyFromS3(
        context: Context,
        functionName: String = "shazam-indexer",
        bucket: String,
        key: String,
        requestId: String? = null
    ): String {

        val creds = BasicAWSCredentials(AwsConfig.ACCESS_KEY, AwsConfig.SECRET_KEY)
        val regionEnum = normalizeRegion()
        val client = AWSLambdaClient(creds).apply {
            setRegion(Region.getRegion(regionEnum))
        }

        val body = JSONObject()
            .put("s3_bucket", bucket)
            .put("s3_key", key)

        if (requestId != null)
            body.put("request_id", requestId)

        val req = InvokeRequest()
            .withFunctionName(functionName)
            .withPayload(ByteBuffer.wrap(body.toString().toByteArray(StandardCharsets.UTF_8)))

        val res = client.invoke(req)
        return String(res.payload.array(), StandardCharsets.UTF_8)
    }
}
