package com.leap.leaplaughlove.iam.account;

import com.leap.leaplaughlove.common.client.AccountRestClientConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Brings in the shared RestClient iam-app uses to reach account-app.
 */
@Configuration
@Import(AccountRestClientConfig.class)
public class AccountClientConfig {

    /**
     * Protected constructor for the configuration class.
     */
    protected AccountClientConfig() {
    }
}
