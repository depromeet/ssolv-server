package org.depromeet.team3.config

import org.testcontainers.containers.MySQLContainer

object MySqlTestContainer {
    val instance: MySQLContainer<*> by lazy {
        MySQLContainer("mysql:8.0")
            .withDatabaseName("ssolv_test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(true)
            .also { it.start() }
    }
}
