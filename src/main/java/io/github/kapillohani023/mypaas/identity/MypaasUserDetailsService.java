package io.github.kapillohani023.mypaas.identity;

import io.github.kapillohani023.mypaas.security.MypaasPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class MypaasUserDetailsService implements UserDetailsService {

    private final UserService users;

    public MypaasUserDetailsService(UserService users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = users.findByEmail(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        return new MypaasPrincipal(new io.github.kapillohani023.mypaas.security.Actor(
                user.id(), user.email(), user.displayName(), user.role()), user.passwordHash(),
                user.emailVerified(), user.mustChangePassword());
    }
}
