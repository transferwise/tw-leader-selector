package com.transferwise.common.leaderselector.testapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.stereotype.Component;

@SpringBootApplication
@ComponentScan("com.transferwise.common.leaderselector.testapp")
public class Application {
	public static void main(String[] args) {
		try {
			SpringApplication springApplication = new SpringApplication(Application.class);
			springApplication.run(args);
		} catch (Throwable t) {
			t.printStackTrace();
			throw t;
		}
	}
}