package com.vortox.sidecar;

import com.vortox.sidecar.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class VortoxSidecarApplication {

    private static final Logger log = LoggerFactory.getLogger(VortoxSidecarApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(VortoxSidecarApplication.class, args);
    }

    @Bean
    CommandLineRunner logStartup(SkillRegistry registry) {
        return args -> {
            int count = registry.count();
            log.info("Vortox sidecar started — {} skill(s) loaded", count);
            registry.all().forEach(s -> log.info("  skill: {}", s.name()));
        };
    }
}
