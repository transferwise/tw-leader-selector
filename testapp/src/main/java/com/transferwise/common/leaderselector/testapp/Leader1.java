package com.transferwise.common.leaderselector.testapp;

import com.transferwise.common.baseutils.ExceptionUtils;
import com.transferwise.common.leaderselector.Leader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@ConditionalOnProperty(value = "leader1.enabled", matchIfMissing = true)
@Component
/**
 * One node takes leadership and works for 10 seconds.
 */
public class Leader1 extends BaseLeader {
    @Override
    protected String getLeaderId() {
        return "leader1";
    }

    @Override
    @SuppressWarnings("checkstyle:magicnumber")
    protected Leader getLeader() {
        return control -> {
            if (control.shouldStop()) {
                log.error("Leadership is not guaranteed anymore, we should stop.");
            } else {
                log.info("I'm now the leader.");
                ExceptionUtils.doUnchecked(() -> Thread.sleep(10000));
            }
        };
    }
}
