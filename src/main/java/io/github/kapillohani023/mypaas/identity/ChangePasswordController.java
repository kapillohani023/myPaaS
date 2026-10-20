package io.github.kapillohani023.mypaas.identity;

import io.github.kapillohani023.mypaas.core.ValidationException;
import io.github.kapillohani023.mypaas.security.ActorContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class ChangePasswordController {

    private final UserService users;

    public ChangePasswordController(UserService users) {
        this.users = users;
    }

    @GetMapping("/change-password")
    public String page(Model model, CsrfToken csrfToken, HttpServletRequest request) {
        request.getSession(true);
        model.addAttribute("csrfTokenValue", csrfToken.getToken());
        return "change-password";
    }

    @PostMapping("/change-password")
    public String submit(@RequestParam String currentPassword,
                         @RequestParam String newPassword,
                         @RequestParam String confirmPassword,
                         Model model, CsrfToken csrfToken, HttpServletRequest request) {
        request.getSession(true);
        model.addAttribute("csrfTokenValue", csrfToken.getToken());
        if (!newPassword.equals(confirmPassword)) {
            model.addAttribute("error", "Passwords do not match");
            return "change-password";
        }
        try {
            users.changePassword(ActorContext.requireActor().id(), currentPassword, newPassword);
        } catch (ValidationException e) {
            model.addAttribute("error", e.getMessage());
            return "change-password";
        }
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return "redirect:/login?passwordChanged=true";
    }
}
