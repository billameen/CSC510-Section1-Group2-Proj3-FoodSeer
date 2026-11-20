package FoodSeer.service.impl;

import java.util.Map;

import FoodSeer.constant.UserRole;
import FoodSeer.entity.DriverStats;
import FoodSeer.repositories.DriverStatsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import FoodSeer.dto.AuthResponseDto;
import FoodSeer.dto.LoginRequestDto;
import FoodSeer.dto.RegisterRequestDto;
import FoodSeer.entity.User;
import FoodSeer.repositories.UserRepository;
import FoodSeer.security.JwtTokenProvider;
import FoodSeer.service.AuthService;
// generates a starter fitness-data file for new customers below
import FoodSeer.service.FitnessDataService;
import lombok.AllArgsConstructor;

@Service
@AllArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthServiceImpl.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DriverStatsRepository driverStatsRepository;

    // generates a starter fitness-data file for new customers below
    @Autowired
    private FitnessDataService fitnessDataService;

    private PasswordEncoder       passwordEncoder;
    private AuthenticationManager authManager;
    private JwtTokenProvider      jwtService;

    /** Letters, digits, underscore and hyphen; 3 to 50 characters. */
    private static final String USERNAME_PATTERN = "^[A-Za-z0-9_-]{3,50}$";

    /** A plain address: local part, @, domain with a dot. */
    private static final String EMAIL_PATTERN = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$";

    /**
     * Public sign-up. Only customers and drivers can register themselves;
     * staff and admin accounts are created by an admin through
     * {@link #createAccount(RegisterRequestDto)}.
     */
    @Override
    public ResponseEntity<Map<String, String>> register ( final RegisterRequestDto req ) {
        final UserRole role;
        try {
            role = UserRole.parse( req == null ? null : req.role() );
        }
        catch ( final IllegalArgumentException e ) {
            return badRequest( e.getMessage() );
        }
        if ( !role.isSelfRegistrable() ) {
            return badRequest( "Staff and admin accounts are created by an administrator." );
        }
        return save( req, role );
    }

    /**
     * Admin-only account creation. Any of the four roles is allowed.
     */
    @Override
    public ResponseEntity<Map<String, String>> createAccount ( final RegisterRequestDto req ) {
        final UserRole role;
        try {
            role = UserRole.parse( req == null ? null : req.role() );
        }
        catch ( final IllegalArgumentException e ) {
            return badRequest( e.getMessage() );
        }
        return save( req, role );
    }

    /**
     * Validates the request and saves the account with the given role.
     */
    private ResponseEntity<Map<String, String>> save ( final RegisterRequestDto req, final UserRole role ) {
        final String problem = validate( req );
        if ( problem != null ) {
            return badRequest( problem );
        }

        final String hash = passwordEncoder.encode( req.password() );
        final User user = new User( req, hash, role.authority() );
        userRepository.save( user );
        if ( role == UserRole.DRIVER ) {
            final DriverStats driverStats = new DriverStats();
            driverStats.setUsername( req.username() );
            driverStatsRepository.save( driverStats );
        }
        if ( role == UserRole.CUSTOMER ) {
            // so every new customer has fitness data from day one, instead of needing
            // scripts/generate_fitness_data.py run by hand per account
            fitnessDataService.generateFitnessDataIfMissing( req.username() );
        }
        logger.info( "Registered {} as {}", req.username(), role );
        return ResponseEntity.ok( Map.of( "message", "Registered" ) );
    }

    /**
     * @return the first problem with the request, or null if it is valid
     */
    private String validate ( final RegisterRequestDto req ) {
        if ( req.username() == null || !req.username().matches( USERNAME_PATTERN ) ) {
            return "Username must be 3-50 characters: letters, digits, - and _ only";
        }
        if ( userRepository.existsByUsername( req.username() ) ) {
            return "Username already taken";
        }
        if ( req.password() == null || req.password().length() < 8 || req.password().length() > 128 ) {
            return "Password must be 8-128 characters";
        }
        if ( req.email() == null || req.email().length() > 254 || !req.email().matches( EMAIL_PATTERN ) ) {
            return "Please enter a valid email address";
        }
        if ( Boolean.TRUE.equals( userRepository.existsByEmail( req.email() ) ) ) {
            return "Email already in use";
        }
        return null;
    }

    private static ResponseEntity<Map<String, String>> badRequest ( final String message ) {
        logger.info( "Registration rejected: {}", message );
        return ResponseEntity.badRequest().body( Map.of( "error", message ) );
    }

    @Override
    public ResponseEntity<AuthResponseDto> login ( final LoginRequestDto req ) {
        final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken( req.username(),
                req.password() );
        final Authentication authentication = authManager.authenticate( auth );

        final String token = jwtService.generateToken( authentication );
        return ResponseEntity.ok( new AuthResponseDto( token ) );
    }

}
