package io.github.kapillohani023.mypaas.identity;

import io.github.kapillohani023.mypaas.core.DomainException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class VerifyEmailController {

    private final AuthService auth;

    public VerifyEmailController(AuthService auth) {
        this.auth = auth;
    }

    @GetMapping("/verify-email")
    public String verify(@RequestParam(required = false) String token, Model model) {
        if (token == null || token.isBlank()) {
            model.addAttribute("error", "The verification link is incomplete.");
            return "verify-email";
        }
        try {
            auth.verifyEmail(token);
            model.addAttribute("verified", true);
        } catch (DomainException e) {
            model.addAttribute("error", e.getMessage());
        }
        return "verify-email";
    }
}
