package io.github.kapillohani023.mypaas;

import io.github.kapillohani023.mypaas.config.MypaasProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(MypaasProperties.class)
public class MypaasApplication {

    public static void main(String[] args) {
        SpringApplication.run(MypaasApplication.class, args);
    }
}
