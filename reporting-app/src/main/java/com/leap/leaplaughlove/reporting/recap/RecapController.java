package com.leap.leaplaughlove.reporting.recap;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Serves clients their own monthly recap. The client is the one the token was issued to; there is
 * no way to ask for anyone else's. Clients only: see ReportingSecurityConfig.
 */
@RestController
@RequestMapping("/api/reporting/recaps")
public class RecapController {

    private final RecapService recaps;

    /**
     * Creates the controller.
     * @param recaps the service that finds or works out recaps
     */
    public RecapController(RecapService recaps) {
        this.recaps = recaps;
    }

    /**
     * The signed-in client's recap of last month.
     * @param authentication the client's authentication, whose principal is their client ID
     * @return the recap
     */
    @GetMapping("/latest")
    public RecapResponse latest(Authentication authentication) {
        return recaps.latest((UUID) authentication.getPrincipal());
    }
}
