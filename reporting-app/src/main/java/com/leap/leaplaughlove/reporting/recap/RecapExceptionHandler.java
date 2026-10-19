package com.leap.leaplaughlove.reporting.recap;

import com.leap.leaplaughlove.common.web.BaseGlobalExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Renders recap errors as the services' shared JSON error body. Scoped to the recap controller, so
 * the analyst reports' error handling stays as it was.
 */
@RestControllerAdvice(assignableTypes = RecapController.class)
public class RecapExceptionHandler extends BaseGlobalExceptionHandler {

    /**
     * Market data couldn't be reached while a recap was being worked out: a 503, since trying
     * again shortly may well work.
     * @param ex the failure
     * @return the error response
     */
    @ExceptionHandler(RecapPricesUnavailableException.class)
    public ResponseEntity<Map<String, String>> handlePricesUnavailable(RecapPricesUnavailableException ex) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "RECAP_UNAVAILABLE",
                "Your recap can't be built right now because prices are unavailable. Try again shortly.");
    }
}
