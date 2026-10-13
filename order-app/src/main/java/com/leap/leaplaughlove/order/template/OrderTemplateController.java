package com.leap.leaplaughlove.order.template;

import com.leap.leaplaughlove.common.security.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for the authenticated client's order templates, which follow them across
 * devices. Endpoints are secured via Bearer JWT.
 */
@RestController
@RequestMapping("/api/order/templates")
public class OrderTemplateController {

    private final OrderTemplateService service;

    /**
     * Constructs an OrderTemplateController.
     * @param service saves, lists and deletes templates
     */
    public OrderTemplateController(OrderTemplateService service) {
        this.service = service;
    }

    /**
     * Lists the client's templates, newest first.
     * @return the templates
     */
    @GetMapping
    public List<OrderTemplateItem> list() {
        return service.list(SecurityUtils.getAuthenticatedClientId());
    }

    /**
     * Saves a template.
     * @param request what to save
     * @return the saved template; 409 if the name is taken or the client has reached the limit
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderTemplateItem create(@Valid @RequestBody OrderTemplateRequest request) {
        return service.create(SecurityUtils.getAuthenticatedClientId(), request);
    }

    /**
     * Deletes a template.
     * @param templateId the template
     */
    @DeleteMapping("/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID templateId) {
        service.delete(SecurityUtils.getAuthenticatedClientId(), templateId);
    }
}
