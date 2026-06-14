package org.depromeet.team3.batch.restaurant.io

import org.depromeet.team3.batch.restaurant.config.RestaurantImportProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import java.nio.file.Files
import java.nio.file.Path

@Component
@ConditionalOnBean(S3Client::class)
class RestaurantSourceFileService(private val s3Client: S3Client, private val properties: RestaurantImportProperties) {
    fun downloadToTempFile(s3Key: String): Path {
        require(properties.s3.bucket.isNotBlank()) { "restaurant.import.s3.bucket is required" }

        val suffix = s3Key.substringAfterLast('/', "restaurant-source.csv")
        val tempFile = Files.createTempFile("restaurant-import-", "-$suffix")
        val request = GetObjectRequest.builder()
            .bucket(properties.s3.bucket)
            .key(s3Key)
            .build()

        s3Client.getObject(request, tempFile)
        return tempFile
    }
}
