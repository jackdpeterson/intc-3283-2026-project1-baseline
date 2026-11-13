package edu.northwestu.project1p2.security;

import edu.northwestu.project1p2.entities.User;
import edu.northwestu.project1p2.services.OAuthLoginService;
import edu.northwestu.project1p2.support.OidcTestUsers;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthLoginSuccessHandlerTests {

    @Mock
    private OAuthLoginService loginService;

    @Test
    void linkedUserBecomesAnAppUserDetailsPrincipalAndIsRedirectedHome() throws Exception {
        final String email = OidcTestUsers.uniqueEmail("ada");
        when(this.loginService.signIn("google", "sub-1", email, true, "Ada L"))
                .thenReturn(Optional.of(new User(email, "Ada L", "{noop}x")));
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());
        final MockHttpServletResponse response = new MockHttpServletResponse();

        new OAuthLoginSuccessHandler(this.loginService).onAuthenticationSuccess(
                request, response, OidcTestUsers.googleLogin("sub-1", email, true, "Ada L"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/");
        final SecurityContext saved = (SecurityContext) request.getSession()
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(saved).isNotNull();
        assertThat(saved.getAuthentication().getPrincipal()).isInstanceOfSatisfying(AppUserDetails.class,
                details -> assertThat(details.getUsername()).isEqualTo(email));
        assertThat(saved.getAuthentication().isAuthenticated()).isTrue();
    }

    @Test
    void identityTheServiceRejectsIsSentBackToLoginAndGetsNoSession() throws Exception {
        when(this.loginService.signIn(anyString(), anyString(), anyString(), anyBoolean(), anyString()))
                .thenReturn(Optional.empty());
        final MockHttpServletRequest request = new MockHttpServletRequest();
        final MockHttpServletResponse response = new MockHttpServletResponse();

        new OAuthLoginSuccessHandler(this.loginService).onAuthenticationSuccess(
                request, response, OidcTestUsers.googleLogin("sub-2", "x" + '@' + "example.test", false, "X"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?oauth");
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void missingEmailAndNameClaimsAreSentToTheServiceAsBlank() throws Exception {
        when(this.loginService.signIn("google", "sub-3", "", true, "")).thenReturn(Optional.empty());
        final MockHttpServletResponse response = new MockHttpServletResponse();

        new OAuthLoginSuccessHandler(this.loginService).onAuthenticationSuccess(
                new MockHttpServletRequest(), response, OidcTestUsers.googleLogin("sub-3", null, true, null));

        verify(this.loginService).signIn("google", "sub-3", "", true, "");
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?oauth");
    }

    @Test
    void nonOAuthAuthenticationIsRejectedWithoutCallingTheService() throws Exception {
        final MockHttpServletResponse response = new MockHttpServletResponse();

        new OAuthLoginSuccessHandler(this.loginService).onAuthenticationSuccess(
                new MockHttpServletRequest(), response, new TestingAuthenticationToken("u", "p"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?oauth");
        verifyNoInteractions(this.loginService);
    }
}
