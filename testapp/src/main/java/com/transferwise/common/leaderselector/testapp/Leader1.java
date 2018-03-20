package com.transferwise.common.leaderselector.testapp;

import com.transferwise.common.leaderselector.Leader;
import com.transferwise.common.utils.ExceptionUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class Leader1 extends BaseLeader {
    @Override
    protected String getLeaderId() {
        return "leader1";
    }

    @Override
    protected Leader getLeader() {
        return control -> {
            if (control.shouldStop()) {
                log.error("Leadership is not guaranteed anymore, we should stop.");
            } else {
                log.info("I'm now the leader.");
                ExceptionUtils.runUnchecked(() -> Thread.sleep(1));
            }
        };
    }
}
