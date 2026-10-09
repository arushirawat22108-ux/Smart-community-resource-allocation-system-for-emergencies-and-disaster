package com.cras.controller;

import com.cras.dto.*;
import com.cras.entity.*;
import com.cras.repository.*;
import com.cras.security.JwtService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin
public class AuthController {

    private final UserRepository userRepository;
    private final LocationRepository locationRepository;
    private final PasswordEncoder encoder;
    private final JwtService jwtService;

    public AuthController(UserRepository userRepository,
                          LocationRepository locationRepository,
                          PasswordEncoder encoder,
                          JwtService jwtService) {
        this.userRepository = userRepository;
        this.locationRepository = locationRepository;
        this.encoder = encoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest body) {
        if (userRepository.existsByEmail(body.email().toLowerCase())) {
            return ResponseEntity.badRequest().body("Email already registered");
        }

        User user = new User();
        user.setName(body.name());
        user.setEmail(body.email().toLowerCase());
        user.setPasswordHash(encoder.encode(body.password()));

        // SECURITY: public registration can create COMMUNITY_USER only.
        // RESOURCE_MANAGER and ADMIN accounts are created/managed by administrators.
        user.setRole(Role.COMMUNITY_USER);

        if (body.locationId() != null) {
            user.setLocation(locationRepository.findById(body.locationId())
                    .orElseThrow(() -> new RuntimeException("Location not found")));
        }

        User saved = userRepository.save(user);

        return ResponseEntity.ok(new Object() {
            public final Long id = saved.getId();
            public final String email = saved.getEmail();
            public final String role = saved.getRole().name();
        });
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest body) {
        User user = userRepository.findByEmail(body.email().toLowerCase())
                .orElseThrow(() -> new RuntimeException("Invalid email or password"));

        if (!encoder.matches(body.password(), user.getPasswordHash())) {
            throw new RuntimeException("Invalid email or password");
        }

        String token = jwtService.generate(
                user.getEmail(), user.getRole().name());

        return ResponseEntity.ok(new Object() {
            public final String accessToken = token;
            public final String role = user.getRole().name();
            public final String name = user.getName();
        });
    }
}
