package FoodSeer.service;

import java.util.Map;
import org.springframework.http.ResponseEntity;

import FoodSeer.dto.AuthResponseDto;
import FoodSeer.dto.LoginRequestDto;
import FoodSeer.dto.RegisterRequestDto;

public interface AuthService {
    public ResponseEntity<Map<String, String>> register ( final RegisterRequestDto req );

    /**
     * Creates an account of any role. Only an admin may call this, through
     * POST /api/users; the public register endpoint is limited to customers
     * and drivers.
     *
     * @param req
     *            the new account
     * @return 200, or 400 with an error message
     */
    public ResponseEntity<Map<String, String>> createAccount ( final RegisterRequestDto req );

    public ResponseEntity<AuthResponseDto> login ( final LoginRequestDto req );
}
