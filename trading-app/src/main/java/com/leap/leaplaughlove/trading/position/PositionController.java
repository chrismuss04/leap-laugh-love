package com.leap.leaplaughlove.trading.position;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/trading/positions")
public class PositionController {

    private final PositionService positionService;

    public PositionController(PositionService positionService) {
        this.positionService = positionService;
    }
    
    @GetMapping("/accounts/{accountId}")
    public ResponseEntity<PositionsResponse> getPositionsForAccount(@PathVariable UUID accountId) {
        return ResponseEntity.ok(positionService.getPositionsForAuthenticatedClientAccount(accountId));
    }
}
