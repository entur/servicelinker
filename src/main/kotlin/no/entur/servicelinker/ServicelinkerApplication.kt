package no.entur.servicelinker

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class ServicelinkerApplication

fun main(args: Array<String>) {
    runApplication<ServicelinkerApplication>(*args)
}
