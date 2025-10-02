package no.entur.servicelinker

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.integration.annotation.IntegrationComponentScan

@SpringBootApplication
@IntegrationComponentScan
class ServicelinkerApplication

fun main(args: Array<String>) {
	runApplication<ServicelinkerApplication>(*args)
}
