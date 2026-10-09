package com.cras.config;

import com.cras.entity.*;
import com.cras.repository.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class DataInitializer {

    @Bean
    CommandLineRunner seed(LocationRepository locations,
                           ResourceRepository resources,
                           UserRepository users,
                           PasswordEncoder encoder) {
        return args -> {
            Location dehradun = locations.findAll().stream()
                    .filter(l -> l.getName().equalsIgnoreCase("Dehradun"))
                    .findFirst()
                    .orElseGet(() -> locations.save(
                            new Location("Dehradun", 30.3165, 78.0322, 6)));

            if (resources.count() == 0) {
                resources.save(new Resource("Water", "litre", 1000));
                resources.save(new Resource("Food Packet", "packet", 500));
                resources.save(new Resource("Medical Kit", "kit", 50));
                resources.save(new Resource("Blanket", "piece", 100));
            }

            if (!users.existsByEmail("manager@cras.local")) {
                User manager = new User();
                manager.setName("CRAS Manager");
                manager.setEmail("manager@cras.local");
                manager.setPasswordHash(encoder.encode("Manager@123"));
                manager.setRole(Role.RESOURCE_MANAGER);
                manager.setLocation(dehradun);
                users.save(manager);
            }

            if (!users.existsByEmail("user@cras.local")) {
                User user = new User();
                user.setName("Community User");
                user.setEmail("user@cras.local");
                user.setPasswordHash(encoder.encode("User@123"));
                user.setRole(Role.COMMUNITY_USER);
                user.setLocation(dehradun);
                users.save(user);
            }

            if (!users.existsByEmail("admin@cras.local")) {
                User admin = new User();
                admin.setName("CRAS Admin");
                admin.setEmail("admin@cras.local");
                admin.setPasswordHash(encoder.encode("Admin@123"));
                admin.setRole(Role.ADMIN);
                admin.setLocation(dehradun);
                users.save(admin);
            }
        };
    }
}
