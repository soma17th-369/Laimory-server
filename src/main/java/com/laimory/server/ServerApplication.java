package com.laimory.server;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ServerApplication {

	public static void main(String[] args) {
		// 이중 안전장치 — 권위와 이유는 TimeZoneConfig에 있다(테스트는 main()을 타지 않는다). #371
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
		SpringApplication.run(ServerApplication.class, args);
	}

}
