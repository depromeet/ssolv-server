package org.depromeet.team3.notification.domain

interface FcmClient {
    fun sendMulticast(tokens: List<String>, title: String, body: String, data: Map<String, String>)
}