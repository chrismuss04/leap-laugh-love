package com.leap.leaplaughlove.iam.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Registration reporting contract; excludes personal details and account provisioning data.
 * @param clientId the newly registered client
 * @param registeredAt when the client registered
 */
public record ClientRegistrationEvent(UUID clientId,
        @JsonProperty("registered_at") OffsetDateTime registeredAt) {}
