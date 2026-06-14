package org.depromeet.team3.batch.restaurant.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "restaurant.import")
data class RestaurantImportProperties(
    var enabled: Boolean = false,
    var enrichmentPublishEnabled: Boolean = true,
    var s3: S3 = S3(),
    var defaults: Defaults = Defaults(),
) {
    data class S3(var bucket: String = "", var region: String = "ap-northeast-2")

    data class Defaults(var chunkSize: Int = 3000, var enrichmentPublishLimit: Int = 50_000)
}
