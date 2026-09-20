package com.pms.user.service;

import com.pms.user.entity.User;
import com.pms.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// Bridge between the users table and Spring Security's UserDetails contract: loads an
// account by email and hands it back as login name, password hash, authorities and locked
// flag. Uses the same UserRepository.findActiveByEmailWithRole query as login and the JWT
// filter, so all three see the same permission set.
//
// Note: the login endpoint does NOT go through this class today — AuthService.login reads
// the hash directly for its own rate-limiting and constant-time comparison (H-2). This
// class exists so any other Spring Security path (form login, AuthenticationManager,
// basic-auth) has a standard implementation, and so declaring a UserDetailsService bean
// switches off Spring Boot's fallback in-memory "user" account.
//
// UserService answers "what may I do?" for the browser to display; this class answers "who
// is this and what authorities do they carry?" for Spring Security to enforce.

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    /**
     * Loads one account by email and describes it to Spring Security: email as username,
     * BCrypt hash, authority list built from the role's permission codes (never role
     * names — ADR-001, so the matrix stays editable without a redeploy), and the locked flag.
     *
     * <p>Throws UsernameNotFoundException, not NotFoundException, when no active account
     * matches — Spring Security maps this specific type to a generic "bad credentials"
     * answer instead of a 500 that would confirm the email doesn't exist.
     */
    @Override
    // readOnly: role and permissions are walked below; without a transaction the session
    // could already be closed and throw LazyInitializationException.
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        // LEFT JOIN on permissions inside this query matters: a role with zero permissions
        // is legitimate, and an inner join would make such an account vanish, reporting
        // "wrong password" for an account whose password is fine.
        User user = userRepository.findActiveByEmailWithRole(email)
                .orElseThrow(() -> new UsernameNotFoundException("Utilisateur introuvable : " + email));

        // Raw codes, no "ROLE_" prefix: hasAuthority('X') compares the string exactly, and
        // a prefix here would break every @PreAuthorize in the project.
        List<SimpleGrantedAuthority> authorities = user.getRole().getPermissions().stream()
                .map(p -> new SimpleGrantedAuthority(p.getCode()))
                .toList();

        // Fully-qualified because Spring Security also has a class named "User".
        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getEmail())
                .password(user.getPasswordHash())
                .authorities(authorities)
                // active=false means UserCrudService.deactivate switched the account off;
                // without this, a deactivated person could still pass the password check.
                .accountLocked(!user.isActive())
                .build();
    }
}
