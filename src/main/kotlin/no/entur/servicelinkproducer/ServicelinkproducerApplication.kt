package no.entur.servicelinkproducer

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.integration.annotation.IntegrationComponentScan

@SpringBootApplication
@IntegrationComponentScan
class ServicelinkproducerApplication

fun main(args: Array<String>) {
	runApplication<ServicelinkproducerApplication>(*args)
}
