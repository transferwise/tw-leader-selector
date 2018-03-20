package com.transferwise.common.leaderselector.testapp;

import com.transferwise.common.leaderselector.Leader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class Leader3 extends BaseLeader {
    @Override
    protected String getLeaderId() {
        return "leader3";
    }

    @Override
    protected Leader getLeader() {
        return control -> {
            throw new RuntimeException("Something went wrong in the worker.");
        };
    }
}
