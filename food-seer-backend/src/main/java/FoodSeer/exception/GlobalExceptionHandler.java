package FoodSeer.exception;

import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns exceptions into JSON error responses with the right status.
 *
 * <ul>
 * <li>AccessDeniedException, SecurityException: 403</li>
 * <li>ResourceNotFoundException: 404</li>
 * <li>IllegalStateException, a database constraint, a concurrent change: 409</li>
 * <li>IllegalArgumentException, an unreadable body, a missing or mistyped
 * parameter: 400</li>
 * <li>anything else: 500 with a generic message</li>
 * </ul>
 *
 * A 500 never includes the exception's message, which can contain SQL, class
 * names or data. The details go to the server log only.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger( GlobalExceptionHandler.class );

    /** The only message a client sees for an unexpected error. */
    static final String GENERIC_ERROR = "Something went wrong on our side. Please try again.";

    private static ResponseEntity<ErrorDetails> respond ( final HttpStatus status, final String message,
            final WebRequest request ) {
        return new ResponseEntity<>( new ErrorDetails( new Date(), message, request.getDescription( false ) ),
                status );
    }

    /**
     * Handles ResourceNotFoundException.
     *
     * @param ex
     *            The thrown exception
     * @param request
     *            The web request
     * @return 404
     */
    @ExceptionHandler ( ResourceNotFoundException.class )
    public ResponseEntity<ErrorDetails> handleResourceNotFoundException ( final ResourceNotFoundException ex,
            final WebRequest request ) {
        return respond( HttpStatus.NOT_FOUND, ex.getMessage(), request );
    }

    /**
     * Handles bean-validation errors.
     *
     * @param ex
     *            The thrown exception
     * @param request
     *            The web request
     * @return 400 with the first field error
     */
    @ExceptionHandler ( MethodArgumentNotValidException.class )
    public ResponseEntity<ErrorDetails> handleValidationExceptions ( final MethodArgumentNotValidException ex,
            final WebRequest request ) {
        final String message = ex.getBindingResult().getFieldError() == null ? "Invalid request"
                : ex.getBindingResult().getFieldError().getDefaultMessage();
        return respond( HttpStatus.BAD_REQUEST, message, request );
    }

    /**
     * Bad input the service rejected.
     *
     * @param ex
     *            The thrown exception
     * @param request
     *            The web request
     * @return 400 with the message
     */
    @ExceptionHandler ( IllegalArgumentException.class )
    public ResponseEntity<ErrorDetails> handleIllegalArgument ( final IllegalArgumentException ex,
            final WebRequest request ) {
        return respond( HttpStatus.BAD_REQUEST, ex.getMessage(), request );
    }

    /**
     * A body that isn't valid JSON for the endpoint, or a missing or mistyped
     * parameter.
     *
     * @param ex
     *            The thrown exception
     * @param request
     *            The web request
     * @return 400
     */
    @ExceptionHandler ( { HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class } )
    public ResponseEntity<ErrorDetails> handleMalformedRequest ( final Exception ex, final WebRequest request ) {
        logger.debug( "Malformed request: {}", ex.getMessage() );
        return respond( HttpStatus.BAD_REQUEST, "The request is missing a value or has one in the wrong format.",
                request );
    }

    /**
     * The request conflicts with the current state, for example rating an
     * order twice or deleting the last admin.
     *
     * @param ex
     *            The thrown exception
     * @param request
     *            The web request
     * @return 409 with the message
     */
    @ExceptionHandler ( IllegalStateException.class )
    public ResponseEntity<ErrorDetails> handleIllegalState ( final IllegalStateException ex,
            final WebRequest request ) {
        return respond( HttpStatus.CONFLICT, ex.getMessage(), request );
    }

    /**
     * Two requests changed the same order at once (the @Version check), for
     * example two drivers claiming it. The first wins.
     *
     * @param ex
     *            The thrown exception
     * @param request
     *            The web request
     * @return 409
     */
    @ExceptionHandler ( ObjectOptimisticLockingFailureException.class )
    public ResponseEntity<ErrorDetails> handleConcurrentChange ( final ObjectOptimisticLockingFailureException ex,
            final WebRequest request ) {
        return respond( HttpStatus.CONFLICT, "Someone else changed this order at the same time. Refresh and try again.",
                request );
    }

    /**
     * A database constraint failed. The message names tables and columns, so
     * it stays in the log.
     *
     * @param ex
     *            The thrown exception
     * @param request
     *            The web request
     * @return 409 with a generic message
     */
    @ExceptionHandler ( DataIntegrityViolationException.class )
    public ResponseEntity<ErrorDetails> handleDataIntegrity ( final DataIntegrityViolationException ex,
            final WebRequest request ) {
        logger.warn( "Constraint violation on {}", request.getDescription( false ), ex );
        return respond( HttpStatus.CONFLICT, "This change conflicts with existing data.", request );
    }

    /**
     * Signed in, but not allowed.
     *
     * @param ex
     *            The thrown exception
     * @param request
     *            The web request
     * @return 403
     */
    @ExceptionHandler ( { AccessDeniedException.class, SecurityException.class } )
    public ResponseEntity<ErrorDetails> handleAccessDeniedException ( final RuntimeException ex,
            final WebRequest request ) {
        final String message = ex instanceof SecurityException && ex.getMessage() != null ? ex.getMessage()
                : "Access Denied";
        return respond( HttpStatus.FORBIDDEN, message, request );
    }

    /**
     * Failed login.
     *
     * @param ex
     *            The thrown exception
     * @param request
     *            The web request
     * @return 401
     */
    @ExceptionHandler ( AuthenticationException.class )
    public ResponseEntity<ErrorDetails> handleAuthenticationException ( final AuthenticationException ex,
            final WebRequest request ) {
        return respond( HttpStatus.UNAUTHORIZED, "Unauthorized: " + ex.getMessage(), request );
    }

    /**
     * Wrong HTTP method for a path that exists.
     *
     * @param ex
     *            The thrown exception
     * @param request
     *            The web request
     * @return 405
     */
    @ExceptionHandler ( HttpRequestMethodNotSupportedException.class )
    public ResponseEntity<ErrorDetails> handleMethodNotSupported ( final HttpRequestMethodNotSupportedException ex,
            final WebRequest request ) {
        return respond( HttpStatus.METHOD_NOT_ALLOWED, ex.getMessage(), request );
    }

    /**
     * Body sent with an unsupported content type.
     *
     * @param ex
     *            The thrown exception
     * @param request
     *            The web request
     * @return 415
     */
    @ExceptionHandler ( HttpMediaTypeNotSupportedException.class )
    public ResponseEntity<ErrorDetails> handleMediaType ( final HttpMediaTypeNotSupportedException ex,
            final WebRequest request ) {
        return respond( HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getMessage(), request );
    }

    /**
     * Anything unexpected. The client gets a generic message; the stack trace
     * goes to the log.
     *
     * @param ex
     *            The thrown exception
     * @param request
     *            The web request
     * @return 500
     */
    @ExceptionHandler ( Exception.class )
    public ResponseEntity<ErrorDetails> handleGlobalException ( final Exception ex, final WebRequest request ) {
        logger.error( "Unhandled error on {}", request.getDescription( false ), ex );
        return respond( HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_ERROR, request );
    }
}
