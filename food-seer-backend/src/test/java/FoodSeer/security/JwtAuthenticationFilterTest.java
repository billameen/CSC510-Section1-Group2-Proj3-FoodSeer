package FoodSeer.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {

    private JwtTokenProvider tokenProvider;
    private UserDetailsService userDetailsService;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setup() {
        tokenProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(tokenProvider, "jwtSecret",
                "SuperSecretKeySuperSecretKey123456"); // 32+ chars for HS256
        ReflectionTestUtils.setField(tokenProvider, "jwtExpirationDate", 3600000L);

        userDetailsService = mock(UserDetailsService.class);
        filter = new JwtAuthenticationFilter(tokenProvider, userDetailsService);

        SecurityContextHolder.clearContext();
    }

    @Test
    void testFilterAuthenticatesUser() throws Exception {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("testuser", null);

        String token = tokenProvider.generateToken(auth);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        User testUser = new User("testuser", "", java.util.Collections.emptyList());
        when(userDetailsService.loadUserByUsername("testuser")).thenReturn(testUser);

        filter.doFilterInternal(request, response, chain);

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("testuser", SecurityContextHolder.getContext().getAuthentication().getName());
    }

    @Test
    void testNoTokenSkipsAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void testInvalidTokenClearsAnyExistingAuthentication() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("leftover", null, java.util.Collections.emptyList()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer not.a.token");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNotNull(chain.getRequest(), "the request still continues, unauthenticated");
        verifyNoInteractions(userDetailsService);
    }

    @Test
    void testTokenForDeletedUserLeavesRequestUnauthenticated() throws Exception {
        String token = tokenProvider.generateToken(new UsernamePasswordAuthenticationToken("gone", null));
        when(userDetailsService.loadUserByUsername("gone"))
                .thenThrow(new org.springframework.security.core.userdetails.UsernameNotFoundException("gone"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilterInternal(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
