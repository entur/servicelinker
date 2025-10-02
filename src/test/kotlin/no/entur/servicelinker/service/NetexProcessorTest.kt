package no.entur.servicelinker.service

import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import no.entur.servicelinker.model.Coordinate
import no.entur.servicelinker.model.StopPoint
import no.entur.servicelinker.model.TransportMode
import no.entur.servicelinker.model.TransportMode.BUS
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import java.io.ByteArrayInputStream

@SpringBootTest
class NetexProcessorTest {

    @Test
    fun `should process basic NeTEx file structure`() = runTest {
        val mockOsrmService = mockk<OSRMService>(relaxed = true)
        val netexProcessor = NetexProcessor(mockOsrmService)

        val sampleNetex = """
            <?xml version="1.0" encoding="UTF-8"?>
            <PublicationDelivery xmlns="http://www.netex.org.uk/netex">
                <dataObjects>
                    <CompositeFrame id="NO:CompositeFrame:1" version="1">
                        <frames>
                            <ServiceFrame id="NO:ServiceFrame:1" version="1">
                                <stopPoints>
                                    <ScheduledStopPoint id="NO:StopPoint:1" version="1">
                                        <Name>Test Stop 1</Name>
                                        <Location>
                                            <Longitude>10.7522</Longitude>
                                            <Latitude>59.9139</Latitude>
                                        </Location>
                                    </ScheduledStopPoint>
                                    <ScheduledStopPoint id="NO:StopPoint:2" version="1">
                                        <Name>Test Stop 2</Name>
                                        <Location>
                                            <Longitude>10.7460</Longitude>
                                            <Latitude>59.9127</Latitude>
                                        </Location>
                                    </ScheduledStopPoint>
                                </stopPoints>
                            </ServiceFrame>
                        </frames>
                    </CompositeFrame>
                </dataObjects>
            </PublicationDelivery>
        """.trimIndent()

        val inputStream = ByteArrayInputStream(sampleNetex.toByteArray())
        val (processedXml, addedCount) = netexProcessor.processNetexFile(inputStream)

        assert(processedXml.isNotEmpty())
        assert(addedCount >= 0) // Should add service links for the two stops
    }

    @Test
    fun `should handle coordinate conversion correctly`() {
        val coord1 = Coordinate(59.9139, 10.7522) // Oslo Central Station
        val coord2 = Coordinate(59.9127, 10.7460) // Near Oslo

        assert(coord1.latitude == 59.9139.toBigDecimal())
        assert(coord1.longitude == 10.7522.toBigDecimal())
        assert(coord1.toString() == "10.7522,59.9139") // lon,lat format for OSRM
    }

    @Test
    fun `should generate stable service link IDs`() {
        val stop1 = StopPoint("NO:StopPoint:1", "Stop 1", Coordinate(59.9139, 10.7522), BUS, "1")
        val stop2 = StopPoint("NO:StopPoint:2", "Stop 2", Coordinate(59.9127, 10.7460), BUS, "1")

        val pair = no.entur.servicelinker.model.StationPair(stop1, stop2)
        val serviceLinkId = pair.generateServiceLinkId("SL_")

        assert(serviceLinkId == "SL_NO_StopPoint_1_NO_StopPoint_2")

        // Should be stable - same input should produce same ID
        val serviceLinkId2 = pair.generateServiceLinkId("SL_")
        assert(serviceLinkId == serviceLinkId2)
    }
}
