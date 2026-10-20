package io.github.kapillohani023.mypaas.identity;

import io.github.kapillohani023.mypaas.config.MypaasProperties;
import io.github.kapillohani023.mypaas.core.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class PasswordResetController {

    private final AuthService auth;
    private final MypaasProperties properties;

    public PasswordResetController(AuthService auth, MypaasProperties properties) {
        this.auth = auth;
        this.properties = properties;
    }

    @GetMapping("/forgot-password")
    public String forgot(Model model, CsrfToken csrfToken, HttpServletRequest request) {
        request.getSession(true);
        model.addAttribute("csrfTokenValue", csrfToken.getToken());
        return "forgot-password";
    }

    @PostMapping("/forgot-password")
    public String requestReset(@RequestParam String email, Model model, CsrfToken csrfToken,
                               HttpServletRequest request) {
        request.getSession(true);
        auth.requestPasswordReset(email, properties.getPublicBaseUrl());
        model.addAttribute("csrfTokenValue", csrfToken.getToken());
        model.addAttribute("sent", true);
        model.addAttribute("email", email);
        return "forgot-password";
    }

    @GetMapping("/reset-password")
    public String reset(@RequestParam(required = false) String token, Model model, CsrfToken csrfToken,
                        HttpServletRequest request) {
        request.getSession(true);
        model.addAttribute("csrfTokenValue", csrfToken.getToken());
        model.addAttribute("token", token);
        return "reset-password";
    }

    @PostMapping("/reset-password")
    public String submitReset(@RequestParam String token,
                              @RequestParam String newPassword,
                              @RequestParam String confirmPassword,
                              Model model, CsrfToken csrfToken, HttpServletRequest request) {
        request.getSession(true);
        model.addAttribute("csrfTokenValue", csrfToken.getToken());
        model.addAttribute("token", token);
        if (!newPassword.equals(confirmPassword)) {
            model.addAttribute("error", "Passwords do not match");
            return "reset-password";
        }
        try {
            auth.resetPassword(token, newPassword);
            return "redirect:/login?reset=true";
        } catch (DomainException e) {
            model.addAttribute("error", e.getMessage());
            return "reset-password";
        }
    }
}
