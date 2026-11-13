package edu.northwestu.project1p2.controllers;

import edu.northwestu.project1p2.security.AppUserDetails;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Renders the sign-in page. The POST to {@code /login} is handled by Spring
 * Security's form-login filter (see {@code SecurityConfig}), not by this controller.
 */
@Controller
@RequestMapping("/login")
public class LoginController {

    /**
     * Query-string flag → fixed banner. Only the flag's presence is read, so the
     * query string can never inject text into the page. First match wins.
     */
    private static final String ERROR = "error";

    private static final String SUCCESS = "success";

    private static final List<Notice> NOTICES = List.of(
            new Notice(ERROR, ERROR, "Invalid email or password.", false),
            new Notice("unverified", ERROR,
                    "Confirm your email address before signing in. Check your inbox for the link.", true),
            new Notice("oauth", ERROR,
                    "Google sign-in failed. Make sure your Google email address is verified, then try again.", false),
            new Notice("verified", SUCCESS, "Email confirmed. You can sign in now.", false));

    @GetMapping
    public String login(@AuthenticationPrincipal final @Nullable AppUserDetails currentUser,
                        @RequestParam final Map<String, String> params,
                        final Model model) {
        if (currentUser != null) {
            return "redirect:/";
        }
        NOTICES.stream()
                .filter(notice -> params.containsKey(notice.flag()))
                .findFirst()
                .ifPresent(notice -> {
                    model.addAttribute(notice.kind(), notice.message());
                    model.addAttribute("showResend", notice.offerResend());
                });
        model.addAttribute("section", "login");
        return "login";
    }

    /** A banner shown for one query-string flag; {@code kind} is "error" or "success". */
    private record Notice(String flag, String kind, String message, boolean offerResend) {
    }
}
