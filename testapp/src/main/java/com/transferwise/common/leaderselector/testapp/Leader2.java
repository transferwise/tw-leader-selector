package com.transferwise.common.leaderselector.testapp;

import com.transferwise.common.baseutils.ExceptionUtils;
import com.transferwise.common.leaderselector.Leader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@ConditionalOnProperty(value = "leader2.enabled", matchIfMissing = true)
@Component
public class Leader2 extends BaseLeader {
    @Override
    protected String getLeaderId() {
        return "leader2";
    }

    @Override
    @SuppressWarnings("checkstyle:magicnumber")
    protected Leader getLeader() {
        return control -> {
            for (int i = 0; i < 10; i++) {
                if (control.shouldStop()) {
                    return;
                }
                log.info("Doing work for chunk " + i + ".");
                ExceptionUtils.doUnchecked(() -> Thread.sleep(1));
            }
        };
    }
}
