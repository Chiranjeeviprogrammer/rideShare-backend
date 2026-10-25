package com.rideshare.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableJpaRepositories(basePackages = "com.rideshare.backend.repository")
@EnableRedisRepositories(basePackages = {})
public class RideshareApplication {

	public static void main(String[] args) {
		SpringApplication.run(RideshareApplication.class, args);
	}

}
