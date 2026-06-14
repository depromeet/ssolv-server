package org.depromeet.team3.batch.restaurant.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client

@Configuration
@ConditionalOnProperty(prefix = "restaurant.import", name = ["enabled"], havingValue = "true")
class S3Config(private val properties: RestaurantImportProperties) {

    @Bean
    fun restaurantImportS3Client(): S3Client = S3Client.builder()
        .region(Region.of(properties.s3.region))
        .build()
}
