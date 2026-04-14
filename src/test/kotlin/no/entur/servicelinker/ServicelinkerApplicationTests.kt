package no.entur.servicelinker

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@TestPropertySource(
    properties = [
        "osrm.urls.default=http://mock-osrm.example.com",
        "osrm.urls.bus=http://mock-osrm.example.com",
        "osrm.urls.rail=http://mock-osrm.example.com",
        "osrm.urls.ferry=http://mock-osrm.example.com",
        "osrm.urls.water=http://mock-osrm.example.com",
    ],
)
class ServicelinkerApplicationTests {
    @Test
    fun contextLoads() {
        // Test that the Spring context loads successfully with all configurations
    }
}
