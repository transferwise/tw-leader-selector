package com.transferwise.common.leaderselector.testapp;

import com.transferwise.common.leaderselector.Leader;
import com.transferwise.common.utils.ExceptionUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
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
                ExceptionUtils.runUnchecked(() -> Thread.sleep(1));
            }
        };
    }
}
