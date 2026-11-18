package com.benchmark.lab_result_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LabResultServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(LabResultServiceApplication.class, args);
	}

}
