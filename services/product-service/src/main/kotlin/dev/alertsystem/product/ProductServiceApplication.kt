package dev.alertsystem.product

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class ProductServiceApplication

fun main(args: Array<String>) {
    runApplication<ProductServiceApplication>(*args)
}
