package com.example.soundlens.aws

import com.amazonaws.HttpMethod
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Region
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest
import java.util.Date

object Presigner {

    fun presign(bucket: String, key: String, ttlSeconds: Int = 3600): String {
        val creds = BasicAWSCredentials(AwsConfig.ACCESS_KEY, AwsConfig.SECRET_KEY)
        val s3 = AmazonS3Client(creds).apply {
            setRegion(Region.getRegion(AwsConfig.REGION))
        }
        val exp = Date(System.currentTimeMillis() + ttlSeconds * 1000L)
        val req = GeneratePresignedUrlRequest(bucket, key, HttpMethod.GET).apply {
            expiration = exp
        }
        return s3.generatePresignedUrl(req).toString()
    }

    fun mapGenreToFolder(genreRaw: String?): String {
        val g = (genreRaw ?: "").trim().lowercase()
        return when (g) {
            "classic", "clásica", "clasica", "classical" -> "Clásica"
            "soundtrack", "ost", "scores" -> "Soundtrack"
            "k-pop", "kpop" -> "K-Pop"
            "mexican", "mexicana", "mexicanas" -> "Mexicanas"
            "reggaeton" -> "Reggaeton"
            "rock" -> "Rock"
            "pop" -> "Pop"
            "electronic", "electronica", "electrónica" -> "Electronica"
            "animation", "animated", "animación" -> "Animation"
            "villancicos", "navidad", "christmas", "carols" -> "Villancicos"
            else -> "Soundtrack"
        }
    }
}
