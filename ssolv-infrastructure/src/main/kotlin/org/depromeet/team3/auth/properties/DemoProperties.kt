package org.depromeet.team3.auth.properties

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "demo")
data class DemoProperties(
    var email: String = "",
    var password: String = "",
    var nickname: String = "ssolv공식"
)
