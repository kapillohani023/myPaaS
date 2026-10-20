package io.github.kapillohani023.mypaas.notification;

import io.github.kapillohani023.mypaas.config.MypaasProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
public class NotificationConfig {

    @Bean
    public EmailSender emailSender(MypaasProperties properties, JavaMailSender mailSender) {
        if ("smtp".equalsIgnoreCase(properties.getNotificationsMode())) {
            return new SmtpEmailSender(mailSender, properties.getMailFrom());
        }
        return new CapturingEmailSender();
    }
}
