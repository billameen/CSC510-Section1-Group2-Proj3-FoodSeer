package FoodSeer.security;

import java.util.HashSet;
import java.util.Set;

import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import FoodSeer.constant.UserRole;
import FoodSeer.entity.User;
import FoodSeer.repositories.UserRepository;
import lombok.AllArgsConstructor;

/**
 * Supports finding and logging in a user by username or email.
 */
@Service
@AllArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    /** Link to userRepository */
    private UserRepository userRepository;

    /**
     * Returns UserDetails for the user associated with the username or email
     * address. The returned principal name is always the username.
     *
     * @param usernameOrEmail
     *            username or email to search for
     * @return UserDetails object representing the user.
     */
    @Override
    public UserDetails loadUserByUsername ( final String usernameOrEmail ) throws UsernameNotFoundException {
        final User user = userRepository.findByUsernameOrEmail( usernameOrEmail, usernameOrEmail )
                .orElseThrow( () -> new UsernameNotFoundException(
                        "User " + usernameOrEmail + " does not exist with the given username or email." ) );
        if ( !user.isActive() ) {
            throw new DisabledException( "This account has been deleted." );
        }
        // An account whose stored role is empty or invented (possible before
        // roles were validated) can't be authorized for anything; say so at
        // login instead of failing on an empty authority.
        final UserRole role = UserRole.fromAuthority( user.getRole() );
        if ( role == null ) {
            throw new DisabledException( "This account has no valid role. Ask an administrator to assign one." );
        }
        final Set<GrantedAuthority> authorities = new HashSet<GrantedAuthority>();
        authorities.add( new SimpleGrantedAuthority( role.authority() ) );

        // The principal is always the username, even when the user logged in
        // with their email. Tokens, getCurrentUser() and ownership checks all
        // compare against the username.
        return new org.springframework.security.core.userdetails.User( user.getUsername(), user.getPassword(),
                authorities );
    }
}
