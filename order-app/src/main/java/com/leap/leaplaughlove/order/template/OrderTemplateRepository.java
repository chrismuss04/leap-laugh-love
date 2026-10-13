package com.leap.leaplaughlove.order.template;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Stores clients' saved ticket setups. */
@Repository
public interface OrderTemplateRepository extends JpaRepository<OrderTemplate, UUID> {

    /**
     * Lists a client's templates, newest first.
     * @param clientId the client
     * @return their templates
     */
    List<OrderTemplate> findByClientIdOrderByCreatedAtDescTemplateIdDesc(UUID clientId);

    /**
     * Counts a client's templates.
     * @param clientId the client
     * @return how many they have
     */
    long countByClientId(UUID clientId);

    /**
     * Tells whether the client already has a template with this name, ignoring case.
     * @param clientId the client
     * @param name the name
     * @return true if one exists
     */
    @Query("SELECT COUNT(t) > 0 FROM OrderTemplate t WHERE t.clientId = :clientId AND LOWER(t.name) = LOWER(:name)")
    boolean existsByClientIdAndNameIgnoreCase(@Param("clientId") UUID clientId, @Param("name") String name);

    /**
     * Finds a template only if it belongs to the client.
     * @param templateId the template
     * @param clientId the client
     * @return the template, if the client owns it
     */
    Optional<OrderTemplate> findByTemplateIdAndClientId(UUID templateId, UUID clientId);

    /**
     * Locks the client's row for the rest of the transaction, so two saves at once can't both pass
     * the limit check.
     * @param clientId the client
     * @return the client ID if the client exists
     */
    @Query(value = "SELECT client_id FROM iam.clients WHERE client_id = :clientId FOR UPDATE", nativeQuery = true)
    List<Object> lockClient(@Param("clientId") UUID clientId);
}
