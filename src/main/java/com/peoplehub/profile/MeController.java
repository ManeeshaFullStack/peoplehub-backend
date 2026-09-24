package com.peoplehub.profile;

import com.peoplehub.security.principal.AuthenticatedPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/me} (b2-3, B2-3/19; b2-4, B2-4/O12; Spec 13, 13.0): the signed-in employee's own
 * profile and welcome state. Needs an access token; takes no id from the request at all.
 */
@RestController
@RequestMapping("/me")
public class MeController {

    private final MeService meService;

    public MeController(MeService meService) {
        this.meService = meService;
    }

    @GetMapping
    public MeResponse me(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return meService.me(principal);
    }

    /** {@code POST /api/v1/me/welcome/ack}: the "Get started" click on the welcome screen. */
    @PostMapping("/welcome/ack")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acknowledgeWelcome(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        meService.acknowledgeWelcome(principal);
    }
}
