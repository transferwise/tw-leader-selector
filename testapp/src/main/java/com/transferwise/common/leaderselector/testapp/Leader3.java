package com.transferwise.common.leaderselector.testapp;

import com.transferwise.common.leaderselector.Leader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@ConditionalOnProperty(value = "leader3.enabled", matchIfMissing = true)
@Component
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
