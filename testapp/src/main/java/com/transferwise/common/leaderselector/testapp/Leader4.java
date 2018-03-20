package com.transferwise.common.leaderselector.testapp;

import com.transferwise.common.leaderselector.Leader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(value = "leader4.enabled", matchIfMissing = true)
@Slf4j
public class Leader4 extends BaseLeader {
    @Override
    protected String getLeaderId() {
        return "leader4";
    }

    @Override
    protected Leader getLeader() {
        return control -> {
            control.workAsyncUntilShouldStop(() -> {
                    throw new RuntimeException("Something went wrong in the start logic.");
                },
                () -> {
                    throw new RuntimeException("Something went wrong in the stop logic.");
                }
            );
        };
    }
}
