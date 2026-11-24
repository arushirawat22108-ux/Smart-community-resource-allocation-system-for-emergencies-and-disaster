package com.cras.controller;

import com.cras.dto.AuthResponse;
import com.cras.dto.LoginRequest;
import com.cras.dto.MeResponse;
import com.cras.dto.RegisterRequest;
import com.cras.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest body) {
        return authService.register(body);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest body) {
        return authService.login(body);
    }

    /** Lets the frontend validate a stored token on page load. */
    @GetMapping("/me")
    public MeResponse me(Authentication auth) {
        if (auth == null) throw new BadCredentialsException("Not signed in");
        return authService.me(auth.getName());
    }
}
