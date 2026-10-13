package com.leap.leaplaughlove.order.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** The test profile turns order emails off, as native runs and deployments without mail can. */
@SpringBootTest
@DisplayName("Order fill emails when disabled")
class OrderFillMailerDisabledTest {

    @Autowired private ApplicationContext context;

    @Test
    @DisplayName("no mailer exists, so no mail can be sent")
    void noMailer() {
        assertThat(context.getBeansOfType(OrderFillMailer.class)).isEmpty();
    }
}
