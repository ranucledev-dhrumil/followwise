package com.followwise;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.TimeZone;

@SpringBootTest
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    protected static final PostgreSQLContainer postgres;

    static {
        postgres = new PostgreSQLContainer("postgres:16");
        postgres.start();
    }

    @BeforeAll
    static void setupTimezone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }
}
