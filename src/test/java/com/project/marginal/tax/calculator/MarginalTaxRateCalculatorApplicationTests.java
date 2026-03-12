package com.project.marginal.tax.calculator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(
		classes = MarginalTaxRateCalculatorApplication.class,
		properties = {
				"app.ingest.api-key = test-api-key",
		}
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MarginalTaxRateCalculatorApplicationTests {

	@Container
	private static final PostgreSQLContainer<?> postgres;

	@Container
	@SuppressWarnings("resource")
	private static final GenericContainer<?> redis =
			new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
					.withExposedPorts(6379);

	static {
        //noinspection resource
        postgres = new PostgreSQLContainer<>("postgres:15")
				.withDatabaseName("testdb")
				.withUsername("test")
				.withPassword("test");
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
		registry.add("spring.data.redis.url",
				() -> "redis://localhost:" + redis.getMappedPort(6379));
	}

	@Test
	void contextLoads() {
	}

}
