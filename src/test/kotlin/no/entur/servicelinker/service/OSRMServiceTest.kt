package no.entur.servicelinker.service

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import kotlinx.coroutines.test.runTest
import no.entur.servicelinker.model.Coordinate
import no.entur.servicelinker.model.TransportMode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OSRMServiceTest {

    private lateinit var wireMockServer: WireMockServer
    private lateinit var osrmService: OSRMService

    @BeforeEach
    fun setUp() {
        wireMockServer = WireMockServer(WireMockConfiguration.options().port(8089))
        wireMockServer.start()

        osrmService = OSRMService(
            osrmBaseUrl = "http://localhost:8089",
            timeoutMs = 5000,
            cacheRoutes = false
        )
    }

    @AfterEach
    fun tearDown() {
        wireMockServer.stop()
    }

    @Test
    fun `should calculate route using real OSRM response data`() = runTest {
        val resource = ClassPathResource("sample-osrm-result-2.json")
        val rawOsrmJson = resource.inputStream.bufferedReader().use { it.readText() }

        wireMockServer.stubFor(
            get(urlPathMatching("/route/v1/driving/.*"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(rawOsrmJson)
                )
        )

        val from = Coordinate(59.913953, 10.75261)
        val to = Coordinate(59.955648, 11.046175)

        val route = osrmService.calculateRoute(from, to, TransportMode.BUS)

        println("Route: $route")
        if (route != null) {
            println("Distance: ${route.distance}")
            println("Duration: ${route.duration}")
            println("Geometry: ${route.geometry}")
            println("Coordinates size: ${route.coordinates.size}")
        }

        assertNotNull(route)
        assertEquals(20842.6, route.distance)
        assertEquals(1341.1, route.duration)

        // Verify the encoded polyline is preserved
        assertEquals("e}tlJybs`A??FEFEn@m@Z]NODEHGHIDEPMFEhC}@NENCBBDBD?DABEDIBK?QAOLy@XoDN}AD]BEBG@I?K?IB]Dk@LsABy@JkALmAJaADe@@GNeBJyANkBBUBQHi@DG@GBI?G?IAG?G@QBQ@MH_@Li@H_@@Kz@}DfBgIH_@rAoGDSR]DIBEDKFWDU@IHc@@IDc@Lq@ViAj@iCBQ@MHDFB@@@@ZNHDNFRJTJF@F@F@JARAh@GPC?E?U@[@a@FqAFe@Fq@Hw@^eDBYD_@z@}HJFv@h@HDF?JACk@AQ?KAQCq@?G?_@?k@?]?[@Y@WBaA?KBqA@o@@G@k@?ABsA?WD_B@cA?IBa@BMBIFOBIBM@c@DmAJkDDgA@YBa@F}@D[B[Jy@Lu@H_@DOJa@Nm@J]Nc@DQJa@F[Fa@Bc@BO@]FoAD{@@]D]Fm@BULw@DOBOBABABE@E@E@GFEHMNWBAJGJCJAL@LDJFJH`BlBJRHLHJ@D@D@B@B@?@@@?D?BCBE@G@G?IAGBQBY?[@G@G?I?I?IAKAIAIG]GKGEEEGCGAkAKOCMAMEQE_@Mo@Ua@e@c@g@uAkB]e@]m@a@w@Wo@Uq@U_AO{@OmAOyAKeBKwCQiGIuBG_BK{AMiAMw@Qq@Uu@]o@]i@_@_@[S[MUG[AU?WBSFSHULSNYT}ArA[VUP[NUFE?]DUCMCME]SUWU[Ua@Oa@Wy@UaAe@sBeD}Nq@kCk@mCUkAQeAUqAGc@q@mESmASgAQq@Ok@Ok@o@qB{BaG_BcEcC{GkBaFeAoC]aAe@qAY}@U{@Ss@Oi@Ke@Mm@Oo@Ii@]kBYaBk@kDqAyHc@cCY}Ai@yCKi@Ie@CMMk@Qw@WqAk@qCoAcGo@yCYkAYgAaAoDsBeHgBgGqBeHc@cBc@eBSgAUiAQoAGi@Gi@ScCM_BIoBMsBCy@EgB@}BCiB?uAAwA@}A?oB@iC@{D@{A?yA?qAAsAAuAAsACsACsACuAEkAEkAEkAGsAIyAOyBIgAImAMqAMoAKoAIm@K}@MeAM}@UeBWaBQqAc@oCa@kCUwAYsBOsAS{AQ{AMqAKkASwBk@wGQeB[cDOoAWwBYkBc@mC]qBg@_C_@cBe@iB[kAe@{A[_A_@gA]aAc@iAg@iAq@{A}@mB]o@y@eBg@aAo@sA_AkBq@wAo@wAa@cA_@}@Wu@m@kBK_@Mc@]oAYkAYqAWkAUoAWqAUwAc@iCYeBw@yEa@eCe@sCiBaLs@eEe@oCWcBg@}Co@}Dm@oDo@wDuB}LqFq\\aB_KYcBSoAS}AYiBS{AOkAQsAUyBi@cFIaAOgBO_BKoAKyAIqAIqAASEw@EkAEiAEsAEkAAoAAoAAuA?mA?uA@uABkAB_BFsBJgDDyABqA@qABuA@oA?wA?iAAkACeAAkAEsAEsAGqAOmCM{AGu@UsCSgCOwBIkAGiAGeAC}@C_ACeAAs@?cA?w@?{@BaABeA@}@FkAFaBHaBJ{AV_Eb@uENaCXqDBa@Dg@NoBJgABa@NyANwAVkBTwAZeBRcAd@qBH_@d@uB`@_BZwAXsAT_A\\yAZqAn@oC`@gBd@oB`@wAZmA`@sAXaAj@cBh@{A`@cA^}@Vk@d@cAb@_Al@gAj@gAhAqBtCuExAaCv@oAd@}@n@kAf@eAj@kA\\y@^}@d@gAb@mAj@eBj@gB^sAb@{A\\sA`@iB\\{AXuA`@yBXaBZmBXuBJ}@LaAHu@Hs@N}AJiAJmALeBHwAHsADuADaBB}A?eCAcBCgBEmAEwAGoBI_CIeBGaBM{COaDIaBKcBEw@GeAUiDIwAQgCOcBIu@KcAKy@M}@QgAWaBY}Ay@qEm@cDm@gDc@}BSgA}@aFwD{SoA}Ge@kCa@wB]gBWuAUkAWgAI]GYIe@Oi@Mg@e@gB}@wC}@wC}A}E_AyC_@kA[gAOi@U{@UcAQ}@OaAQkAOmAMmAGo@IcAK}AOiB]oFa@{FIkAGcAEgAEgAAkAAgA?aA@s@BaA@i@Bm@Dm@Bk@RoCFw@Fs@Ds@Du@DuABsA?mAAuACsAGuAIqAGq@Go@MiAOcAWoAQ}@y@wCmAeEm@yBe@iB_@_Bc@mBa@gB[cBUkAYyAoAkIUiBQwAUiBQqAWwB]}Cs@mHW{BYyBy@iFyBsNu@wEy@aF{@wE}@uE_AsEaAoEeAkEeAkEiAeEkAaEuAsEsEyNeAeD}CuJ_AaDm@yBi@sBe@yBUeAQ_AIa@O{@O{@YwAKo@E[EYAQCU@K@K?OASCMCMGKECICEAGAEEMKOOOOQSW]Wa@Yg@Ue@Qa@Se@]aAM[?IAIAICGEOGIIEEAI@C@EBIIOQMWw@}BgAkDWu@Y}@Sg@M[Q[OUSWSUYWQMYOWKo@WKCEGCCEAC?EBCDCH]Bc@Fu@EY@I@IDIFQRIPKVIZK\\Id@Ef@GBEH??CLAJ?@?HBJBHDFGd@c@vEGz@ARCXe@|FE^KIWSc@_@", route.geometry)

        // Verify the polyline has been decoded into coordinates
        assertNotNull(route.coordinates)
        assert(route.coordinates.isNotEmpty())

        // The decoded coordinates should include the start and end points
        val firstCoordinate = route.coordinates.first()
        val lastCoordinate = route.coordinates.last()

        // Allow for larger precision differences in decoded coordinates due to polyline encoding
        assert(kotlin.math.abs(firstCoordinate[0] - 10.75261) < 0.01) { "Expected longitude ~10.75261, got ${firstCoordinate[0]}" }
        assert(kotlin.math.abs(firstCoordinate[1] - 59.913953) < 0.01) { "Expected latitude ~59.913953, got ${firstCoordinate[1]}" }
        assert(kotlin.math.abs(lastCoordinate[0] - 11.046175) < 0.01) { "Expected longitude ~11.046175, got ${lastCoordinate[0]}" }
        assert(kotlin.math.abs(lastCoordinate[1] - 59.955648) < 0.01) { "Expected latitude ~59.955648, got ${lastCoordinate[1]}" }

        wireMockServer.verify(
            getRequestedFor(urlPathMatching("/route/v1/driving/.*"))
        )
    }

    @Test
    fun `should initialize OSRM service with correct cache behavior`() {
        assertEquals(0, osrmService.getCacheSize())

        osrmService.clearCache()
        assertEquals(0, osrmService.getCacheSize())
    }
}
