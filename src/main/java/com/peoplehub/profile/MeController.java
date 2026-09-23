package com.peoplehub.profile;

import com.peoplehub.security.principal.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/me} (b2-3, B2-3/19; Spec 13.0): the signed-in employee's own profile. Needs an
 * access token; takes no id from the request at all.
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
}
