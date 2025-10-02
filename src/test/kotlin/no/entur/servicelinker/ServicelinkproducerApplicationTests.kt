package no.entur.servicelinker

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@TestPropertySource(properties = [
	"spring.cloud.gcp.project-id=test-project",
	"gcs.bucket.input=test-input-bucket",
	"gcs.bucket.output=test-output-bucket",
	"osrm.base-url=http://mock-osrm.example.com",
	"servicelink.id-prefix=TEST_"
])
class ServicelinkerApplicationTests {

	@Test
	fun contextLoads() {
		// Test that the Spring context loads successfully with all configurations
	}
}
