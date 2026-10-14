package com.example.messaging_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MessagingServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(MessagingServiceApplication.class, args);
	}

}
