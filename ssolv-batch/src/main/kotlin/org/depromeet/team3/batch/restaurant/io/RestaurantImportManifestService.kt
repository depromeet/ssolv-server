package org.depromeet.team3.batch.restaurant.io

import com.fasterxml.jackson.databind.ObjectMapper
import org.depromeet.team3.batch.restaurant.config.RestaurantImportProperties
import org.depromeet.team3.batch.restaurant.model.RestaurantImportManifest
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import java.nio.file.Files
import java.nio.file.Path

@Component
@ConditionalOnBean(S3Client::class)
class RestaurantImportManifestService(
    private val s3Client: S3Client,
    private val objectMapper: ObjectMapper,
    private val properties: RestaurantImportProperties,
) {
    fun loadFromS3(manifestKey: String): RestaurantImportManifest {
        require(properties.s3.bucket.isNotBlank()) { "restaurant.import.s3.bucket is required" }

        val request = GetObjectRequest.builder()
            .bucket(properties.s3.bucket)
            .key(manifestKey)
            .build()
        val body: ResponseBytes<GetObjectResponse> = s3Client.getObjectAsBytes(request)
        return objectMapper.readValue(body.asUtf8String(), RestaurantImportManifest::class.java)
    }

    fun loadFromFile(path: Path): RestaurantImportManifest =
        objectMapper.readValue(Files.readString(path), RestaurantImportManifest::class.java)
}
