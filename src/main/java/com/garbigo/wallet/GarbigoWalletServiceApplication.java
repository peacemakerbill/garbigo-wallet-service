package com.garbigo.wallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;


import io.github.cdimascio.dotenv.Dotenv;

@SpringBootApplication
@EnableFeignClients
@EnableAsync
@EnableScheduling
public class GarbigoWalletServiceApplication {

	public static void main(String[] args) {
		
		// Some networks (VPNs, certain resolvers) hand out synthetic NAT64
        // AAAA records for IPv4-only hosts like sandbox.safaricom.co.ke —
        // Java then tries to connect over an unroutable/disabled IPv6 stack
        // first and fails with "BindException: Permission denied" before
        // ever falling back to IPv4 (curl falls back silently; OkHttp does
        // not reliably). Forcing IPv4-only sockets here avoids that path
        // entirely. Must be set before SpringApplication.run(), and before
        // any HTTP client (OkHttp, Feign, etc.) is constructed.
        System.setProperty("java.net.preferIPv4Stack", "true");

        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        dotenv.entries().forEach(entry -> System.setProperty(entry.getKey(), entry.getValue()));
        
		SpringApplication.run(GarbigoWalletServiceApplication.class, args);
	}

}
