package com.leap.leaplaughlove.order.working;

import com.leap.leaplaughlove.common.security.ClientSessionValidator;
import com.leap.leaplaughlove.common.security.JwtService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-Sent Events stream of the authenticated client's open orders ending: filled, refused when
 * their price was reached, or expired. The browser reads it with fetch(), since EventSource can't
 * send the Authorization header.
 */
@RestController
public class OrderStreamController {

    private final OrderUpdateBroadcaster broadcaster;
    private final ClientSessionValidator sessions;

    /**
     * Constructs an OrderStreamController.
     * @param broadcaster pushes updates to open streams
     * @param sessions checks the client's login is still live while the stream is open
     */
    public OrderStreamController(OrderUpdateBroadcaster broadcaster, ClientSessionValidator sessions) {
        this.broadcaster = broadcaster;
        this.sessions = sessions;
    }

    /**
     * Subscribes the caller to updates on their own orders.
     * @return the SSE emitter streaming updates to the caller
     */
    @GetMapping("/api/order/orders/stream")
    public SseEmitter stream() {
        // Capture the verified identity before leaving the request thread.
        var identity = (JwtService.TokenIdentity) SecurityContextHolder.getContext()
                .getAuthentication().getDetails();
        return broadcaster.subscribe(identity.clientId(), () -> sessions.isActive(
                identity.sessionId(), identity.clientId(), identity.expiresAt()));
    }
}
