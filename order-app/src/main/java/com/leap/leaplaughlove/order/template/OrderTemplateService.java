package com.leap.leaplaughlove.order.template;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Saves, lists and deletes a client's order templates, at most {@value #MAX_PER_CLIENT} each, with
 * names unique among their own (ignoring case).
 */
@Service
public class OrderTemplateService {

    /** How many templates a client may keep. */
    public static final int MAX_PER_CLIENT = 20;

    private final OrderTemplateRepository repository;

    /**
     * Constructs an OrderTemplateService.
     * @param repository stores the templates
     */
    public OrderTemplateService(OrderTemplateRepository repository) {
        this.repository = repository;
    }

    /**
     * Lists the client's templates, newest first.
     * @param clientId the authenticated client
     * @return their templates
     */
    @Transactional(readOnly = true)
    public List<OrderTemplateItem> list(UUID clientId) {
        return repository.findByClientIdOrderByCreatedAtDescTemplateIdDesc(clientId).stream()
                .map(OrderTemplateItem::of).toList();
    }

    /**
     * Saves a template.
     * @param clientId the authenticated client
     * @param request what to save
     * @return the saved template
     * @throws ResponseStatusException conflict (409) if the client already has a template of that
     *         name or has reached the limit
     */
    @Transactional
    public OrderTemplateItem create(UUID clientId, OrderTemplateRequest request) {
        repository.lockClient(clientId);
        if (repository.countByClientId(clientId) >= MAX_PER_CLIENT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You can save up to " + MAX_PER_CLIENT + " templates. Delete one to save another.");
        }
        if (repository.existsByClientIdAndNameIgnoreCase(clientId, request.name().trim())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You already have a template called \"" + request.name().trim() + "\"");
        }
        return OrderTemplateItem.of(repository.saveAndFlush(new OrderTemplate(clientId, request, OffsetDateTime.now())));
    }

    /**
     * Deletes one of the client's templates.
     * @param clientId the authenticated client
     * @param templateId the template
     * @throws ResponseStatusException not found (404) if the client has no such template
     */
    @Transactional
    public void delete(UUID clientId, UUID templateId) {
        OrderTemplate template = repository.findByTemplateIdAndClientId(templateId, clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found"));
        repository.delete(template);
    }
}
