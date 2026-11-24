package com.cras.service;

import com.cras.dto.AuthResponse;
import com.cras.dto.LoginRequest;
import com.cras.dto.MeResponse;
import com.cras.dto.RegisterRequest;
import com.cras.entity.Role;
import com.cras.entity.User;
import com.cras.repository.UserRepository;
import com.cras.security.JwtService;
import com.cras.security.LoginThrottle;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final LoginThrottle throttle;
    private final AuditService audit;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt,
                       LoginThrottle throttle, AuditService audit) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.throttle = throttle;
        this.audit = audit;
    }

    public AuthResponse register(RegisterRequest req) {
        if (users.existsByEmail(req.email())) {
            throw new IllegalArgumentException("Email already registered");
        }
        User u = new User(req.email(), encoder.encode(req.password()), req.fullName(), Role.COMMUNITY_USER);
        users.save(u);
        return new AuthResponse(jwt.generate(u), u.getEmail(), u.getFullName(), u.getRole().name());
    }

    public AuthResponse login(LoginRequest req) {
        String email = req.email().trim();
        String key = email.toLowerCase();
        throttle.check(key);                                          // 429 while locked out
        User u = users.findByEmail(email).orElse(null);
        if (u == null || !encoder.matches(req.password(), u.getPassword())) {
            int left = throttle.fail(key);
            String hint = left > 0 && left <= 3 ? " (" + left + " attempt" + (left == 1 ? "" : "s") + " left)" : "";
            throw new BadCredentialsException("Invalid email or password" + hint);   // 401
        }
        throttle.success(key);
        audit.log(u.getEmail(), "LOGIN", "Signed in as " + u.getRole().name());
        return new AuthResponse(jwt.generate(u), u.getEmail(), u.getFullName(), u.getRole().name());
    }

    public MeResponse me(String email) {
        User u = users.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Session is no longer valid"));
        return new MeResponse(u.getEmail(), u.getFullName(), u.getRole().name());
    }
}
