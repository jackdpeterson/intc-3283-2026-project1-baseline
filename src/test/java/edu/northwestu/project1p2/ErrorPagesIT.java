package edu.northwestu.project1p2;

import edu.northwestu.project1p2.entities.User;
import edu.northwestu.project1p2.repositories.UserRepository;
import edu.northwestu.project1p2.support.ProtectedProbeController;
import jakarta.servlet.RequestDispatcher;
import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Error pages against a REAL server on a random port. MockMvc never performs the
 * container's forward to {@code /error}, so only a real HTTP round trip shows what
 * a browser actually receives: the status code, the designed page, and the
 * navigation for the visitor's sign-in state.
 */
@Tag("integration")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ErrorPagesIT {

    private static final String PASSWORD = "correct-horse-battery";

    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

    // Test framework field injection; app code uses constructor injection only.
    @LocalServerPort
    private int port;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** A browser-like client: keeps cookies, does not follow redirects, asks for HTML. */
    private static HttpClient browser() {
        return HttpClient.newBuilder()
                .cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private HttpResponse<String> getPage(final HttpClient client, final String path)
            throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(this.uri(path)).header("Accept", "text/html").GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postForm(final HttpClient client, final String path, final String form)
            throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(this.uri(path))
                        .header("Accept", "text/html")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(final String path) {
        return URI.create("http://localhost:" + this.port + path);
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Signs a fresh, already-confirmed user in to {@code client} through the real login form. */
    private void signIn(final HttpClient client) throws IOException, InterruptedException {
        final String email = "errors-" + UUID.randomUUID().toString().substring(0, 8) + '@' + "example.test";
        final User user = new User(email, "Error Tester", this.passwordEncoder.encode(PASSWORD));
        user.setVerifiedAt(LocalDateTime.now());
        this.userRepository.save(user);

        final Matcher csrf = CSRF.matcher(this.getPage(client, "/login").body());
        assertThat(csrf.find()).as("login form carries a CSRF token").isTrue();
        final HttpResponse<String> login = this.postForm(client, "/login",
                "email=" + encode(email) + "&password=" + encode(PASSWORD) + "&_csrf=" + encode(csrf.group(1)));
        assertThat(login.statusCode()).isEqualTo(302);
        assertThat(login.headers().firstValue("Location")).hasValueSatisfying(
                location -> assertThat(location).doesNotContain("error"));
    }

    @Test
    void unknownPathIsA404PageForSignedOutVisitors() throws Exception {
        final HttpResponse<String> response;
        try (HttpClient client = browser()) {
            response = this.getPage(client, "/no-such-page");
        }

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/html"));
        assertThat(response.body())
                .contains("class=\"error-code\"", ">404<", "Page not found", "No page at")
                .contains("/no-such-page")
                .contains("href=\"/login\"", "Go to the home page")
                .doesNotContain("Sign out", "Whitelabel");
    }

    @Test
    void unknownPathIsA404PageForSignedInUsersWithTheirNavigation() throws Exception {
        final HttpResponse<String> response;
        try (HttpClient client = browser()) {
            this.signIn(client);
            response = this.getPage(client, "/no-such-page");
        }

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body())
                .contains("Page not found", "Back to the dashboard", "Sign out", "Error Tester")
                .doesNotContain("Go to the home page");
    }

    @Test
    void nestedUnknownPathAndMissingStaticFileAre404s() throws Exception {
        try (HttpClient client = browser()) {
            assertThat(this.getPage(client, "/a/b/c").statusCode()).isEqualTo(404);
            assertThat(this.getPage(client, "/css/does-not-exist.css").statusCode()).isEqualTo(404);
        }
    }

    @Test
    void realProtectedRouteStillRedirectsSignedOutVisitorsToLogin() throws Exception {
        final HttpResponse<String> response;
        try (HttpClient client = browser()) {
            response = this.getPage(client, ProtectedProbeController.PATH);
        }

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValueSatisfying(
                location -> assertThat(location).endsWith("/login"));
    }

    @Test
    void realProtectedRouteWorksWhenSignedIn() throws Exception {
        final HttpResponse<String> response;
        try (HttpClient client = browser()) {
            this.signIn(client);
            response = this.getPage(client, ProtectedProbeController.PATH);
        }

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("protected content");
    }

    @Test
    void postWithoutCsrfTokenIsA403Page() throws Exception {
        final HttpResponse<String> response;
        try (HttpClient client = browser()) {
            response = this.postForm(client, "/register", "username=x");
        }

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body())
                .contains(">403<", "Access denied", "reload the page")
                .doesNotContain("Whitelabel");
    }

    @Test
    void apiClientsGetJsonInsteadOfHtml() throws Exception {
        final HttpResponse<String> response;
        try (HttpClient client = browser()) {
            response = client.send(
                    HttpRequest.newBuilder(this.uri("/no-such-page")).header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"status\":404");
    }

    /** Every status the app could forward to /error renders the designed page with that status. */
    @ParameterizedTest
    @CsvSource({
        "400, Bad request",
        "401, Sign in required",
        "403, Access denied",
        "405, Method not allowed",
        "429, Too many requests",
        "500, Something went wrong",
        "503, Service unavailable",
    })
    void eachErrorStatusRendersTheDesignedPage(final int code, final String title) throws Exception {
        this.mvc.perform(get("/error")
                        .accept(MediaType.TEXT_HTML)
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, code)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/somewhere"))
                .andExpect(status().is(code))
                .andExpect(content().string(containsString("class=\"error-code\"")))
                .andExpect(content().string(containsString(title)))
                .andExpect(content().string(containsString("<title>" + code + " " + title)));
    }
}
