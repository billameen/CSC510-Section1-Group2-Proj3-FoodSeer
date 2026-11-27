package FoodSeer.exception;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    /** Throws whatever the path names. */
    @RestController
    static class Thrower {
        @GetMapping("/denied")
        String denied() {
            throw new AccessDeniedException("nope");
        }

        @GetMapping("/security")
        String security() {
            throw new SecurityException("You are not assigned to this order");
        }

        @GetMapping("/missing")
        String missing() {
            throw new ResourceNotFoundException("Order 9 not found");
        }

        @GetMapping("/conflict")
        String conflict() {
            throw new IllegalStateException("Already rated");
        }

        @GetMapping("/bad")
        String bad() {
            throw new IllegalArgumentException("Rating must be between 0 and 5");
        }

        @GetMapping("/constraint")
        String constraint() {
            throw new DataIntegrityViolationException("Duplicate entry 'x' for key 'users.UK_email'");
        }

        @GetMapping("/boom")
        String boom() {
            throw new NullPointerException("secret internal detail at FoodSeer.SomeClass");
        }

        @GetMapping("/unavailable")
        String unavailable() {
            throw new ChatUnavailableException("The food assistant is unavailable right now.",
                    new java.net.ConnectException("Connection refused"));
        }

        @GetMapping("/number")
        String number(@RequestParam final Long id) {
            return "ok " + id;
        }

        @PostMapping("/body")
        String body(@RequestBody final java.util.Map<String, Object> body) {
            return "ok";
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new Thrower()).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void accessDeniedAndSecurityExceptionAre403() throws Exception {
        mvc.perform(get("/denied")).andExpect(status().isForbidden());
        mvc.perform(get("/security")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You are not assigned to this order"));
    }

    @Test
    void notFoundIs404() throws Exception {
        mvc.perform(get("/missing")).andExpect(status().isNotFound());
    }

    @Test
    void illegalStateIs409AndIllegalArgumentIs400() throws Exception {
        mvc.perform(get("/conflict")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Already rated"));
        mvc.perform(get("/bad")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Rating must be between 0 and 5"));
    }

    @Test
    void anUnavailableChatModelIs503WithAJsonMessage() throws Exception {
        mvc.perform(get("/unavailable")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("The food assistant is unavailable right now."))
                .andExpect(content().string(not(containsString("Connection refused"))));
    }

    @Test
    void malformedInputIs400() throws Exception {
        mvc.perform(get("/number").param("id", "abc")).andExpect(status().isBadRequest());
        mvc.perform(get("/number")).andExpect(status().isBadRequest());
        mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void constraintViolationIs409WithoutSql() throws Exception {
        mvc.perform(get("/constraint")).andExpect(status().isConflict())
                .andExpect(content().string(not(containsString("UK_email"))));
    }

    @Test
    void unknownErrorIs500WithGenericMessage() throws Exception {
        mvc.perform(get("/boom")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value(GlobalExceptionHandler.GENERIC_ERROR))
                .andExpect(content().string(not(containsString("secret internal detail"))));
    }
}
