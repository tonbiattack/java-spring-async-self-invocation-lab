package jp.tonbiattack.debuglab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class AsyncDebugApplication {

    public static void main(String[] args) {
        SpringApplication.run(AsyncDebugApplication.class, args);
    }
}
