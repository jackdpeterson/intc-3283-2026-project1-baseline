# Project 1 Baseline

A small Spring Boot web application you will build on. It already has user
registration, sign-in, email confirmation, and a signed-in dashboard that can
search users. This README explains the main ideas in the code so you can find
your way around and extend it.

**Stack:** Java 27 · Spring Boot 4 · Spring MVC + Thymeleaf · Spring Data JDBC ·
Spring Security · PostgreSQL · Flyway · Docker Compose · smtp-tester

---

## Contents

1. [Getting started](#getting-started)
2. [How a request flows through the app](#how-a-request-flows-through-the-app)
3. [Entities](#entities)
4. [Repositories](#repositories)
5. [Controllers](#controllers)
6. [Postgres connectivity](#postgres-connectivity)
7. [smtp-tester: testing email locally](#smtp-tester-testing-email-locally)
8. [Docker Compose commands](#docker-compose-commands)
9. [Tests and quality gates](#tests-and-quality-gates)
10. [Project layout](#project-layout)

---

## Getting started

### Prerequisites

- **JDK 27** (`java -version` should report 27)
- **Docker Desktop** (or another Docker engine), running
- An IDE such as IntelliJ IDEA (optional, but recommended)

You do **not** need to install Maven or PostgreSQL. The project ships with the
Maven wrapper (`./mvnw`), and Postgres runs in Docker.

### Run it

```sh
./mvnw spring-boot:run
```

Then open:

| URL | What it is |
| --- | --- |
| <http://localhost:8080> | The application |
| <http://localhost:8025> | The smtp-tester inbox (emails the app "sends") |

Try the full flow: **Register**, open the confirmation email at
<http://localhost:8025>, confirm, then **Sign in**.

> **Why didn't I have to start the database?** The `spring-boot-docker-compose`
> dependency runs `docker compose up` for you when the app starts, using
> `compose.yaml`. See [Postgres connectivity](#postgres-connectivity).

---

## How a request flows through the app

Each layer has one job. A browser request travels down the layers and the
response comes back up:

```
 Browser
   │  GET /?q=ada
   ▼
 Controller      IndexController         reads the request, picks a view
   │
   ▼
 Service         UserDirectoryService    business rules ("search, or show recent")
   │
   ▼
 Repository      UserRepository          talks to the database
   │  SELECT ... FROM users WHERE ...
   ▼
 PostgreSQL      users table
```

The controller then hands data to a **Thymeleaf template**
(`src/main/resources/templates/*.html`), which renders the HTML the browser
receives.

Keeping these layers separate is the most important habit in this codebase:

- A **controller** should not contain SQL or business rules.
- A **repository** should not know about HTTP or HTML.
- An **entity** is just data; it does not call anything.

---

## Entities

An **entity** is a Java class that represents one row of a database table.
Each field maps to a column.

`src/main/java/edu/northwestu/project1p2/entities/User.java`:

```java
@Table(name = "users")
public class User {

    @Id
    private @Nullable Long id;          // users.id  (primary key)

    private String email;               // users.email
    private String username;            // users.username
    private String password;            // users.password  (a bcrypt hash, never plain text)
    private LocalDateTime createdAt;    // users.created_at
    private @Nullable LocalDateTime verifiedAt;  // users.verified_at

    public User(final String email, final String username, final String password) { ... }
    ...
}
```

Things to notice:

- **`@Table(name = "users")`** names the table. Without it, Spring Data would
  derive the name from the class name.
- **`@Id`** marks the primary key. It is `null` for a new object and filled in
  by the database on the first `save`.
- **Naming convention:** a camelCase field (`createdAt`) maps to a snake_case
  column (`created_at`) automatically.
- **`@Nullable`** marks fields that can legitimately be `null`. Everything else
  is non-null by default, and the build checks this (see
  [quality gates](#tests-and-quality-gates)).

### Where the table comes from: Flyway migrations

The entity *describes* the table, but it does not *create* it. Tables are
created by SQL files in `src/main/resources/db/migration/`, run by **Flyway**
when the app starts:

| File | What it does |
| --- | --- |
| `V1__add_users.sql` | creates the `users` table |
| `V2__email_verification_tokens.sql` | creates the `email_verification_tokens` table |

Flyway records which migrations have already run (in the
`flyway_schema_history` table) and only runs new ones.

**To change the schema, add a new file**, such as `V3__add_display_name.sql`.
Never edit a migration that has already run: Flyway checks each file's
checksum and refuses to start if an applied migration has changed.

---

## Repositories

A **repository** is the object you use to read and write entities. You write
an **interface**, and Spring Data generates the implementation for you at
startup.

`src/main/java/edu/northwestu/project1p2/repositories/UserRepository.java`:

```java
public interface UserRepository extends CrudRepository<User, Long> {

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    List<User> findTop50ByUsernameContainingIgnoreCaseOrEmailContainingIgnoreCaseOrderByUsernameAsc(
            String username, String email);

    List<User> findTop10ByOrderByCreatedAtDesc();
}
```

### What you get for free

Extending `CrudRepository<User, Long>` (entity type, id type) gives you
methods like:

| Method | SQL it runs (roughly) |
| --- | --- |
| `save(user)` | `INSERT` if `id` is null, otherwise `UPDATE` |
| `findById(id)` | `SELECT ... WHERE id = ?` |
| `findAll()` | `SELECT ... FROM users` |
| `count()` | `SELECT COUNT(*) FROM users` |
| `deleteById(id)` | `DELETE ... WHERE id = ?` |

### Derived queries: the method name is the query

Spring Data reads the **method name** and writes the SQL for you:

| Method name | Becomes |
| --- | --- |
| `existsByEmail(email)` | `SELECT EXISTS(... WHERE email = ?)` |
| `findByEmail(email)` | `SELECT ... WHERE email = ?` |
| `findTop10ByOrderByCreatedAtDesc()` | `... ORDER BY created_at DESC LIMIT 10` |
| `findTop50ByUsernameContainingIgnoreCase...` | `... WHERE UPPER(username) LIKE UPPER(?) ... LIMIT 50` |

The keywords (`find`, `By`, `And`, `Or`, `Containing`, `IgnoreCase`,
`OrderBy`, `Top10`, ...) combine with the **field names** of the entity.
If you misspell a field, the app fails at startup with an error naming the
method, which is a helpful early warning.

Parameters are always passed as **bound parameters**, never pasted into the
SQL string, so these queries are safe from SQL injection.

### Custom SQL with `@Query`

When a name would get unreadable, write the SQL yourself.
`EmailVerificationTokenRepository` has an example:

```java
@Modifying
@Query("UPDATE email_verification_tokens SET used_at = :usedAt WHERE user_id = :userId AND used_at IS NULL")
int invalidateAllForUser(@Param("userId") Long userId, @Param("usedAt") LocalDateTime usedAt);
```

`:userId` and `:usedAt` are named parameters, bound safely. `@Modifying`
marks a query that changes data.

> **Spring Data JDBC vs JPA/Hibernate.** This project uses **Spring Data
> JDBC**, which is deliberately simple: `save` writes the row immediately, and
> there is no lazy loading, no session cache and no automatic dirty checking.
> If you change an entity, nothing happens until you call `save` again.

---

## Controllers

A **controller** handles HTTP requests. Each method is mapped to a URL and an
HTTP method, and returns the **name of a template** to render (or a redirect).

`src/main/java/edu/northwestu/project1p2/controllers/IndexController.java`:

```java
@Controller
public class IndexController {

    private final UserDirectoryService userDirectory;

    public IndexController(final UserDirectoryService userDirectory) {   // (1)
        this.userDirectory = userDirectory;
    }

    @GetMapping("/")                                                      // (2)
    public String index(@AuthenticationPrincipal final @Nullable AppUserDetails currentUser,  // (3)
                        @RequestParam(name = "q", defaultValue = "") final String query,       // (4)
                        final Model model) {                                                   // (5)
        if (currentUser == null) {
            model.addAttribute("section", "home");
            return "index";                                               // (6)
        }
        model.addAttribute("currentUser", currentUser);
        model.addAttribute("users", this.userDirectory.search(query));
        ...
        return "index-authenticated";
    }
}
```

1. **Constructor injection.** Spring sees the constructor needs a
   `UserDirectoryService`, creates one, and passes it in. You never write
   `new UserDirectoryService(...)`. Every component in this project has exactly
   one constructor, and `@Autowired` is not used. A test enforces this
   (`ComponentConstructorTests`).
2. **`@GetMapping("/")`** handles `GET /`. There are matching `@PostMapping`,
   `@PutMapping` and `@DeleteMapping` annotations.
3. **`@AuthenticationPrincipal`** gives you the signed-in user, or `null` when
   nobody is signed in.
4. **`@RequestParam`** reads `?q=...` from the URL.
5. **`Model`** is a map of values the template can display.
6. **The return value** is a template name: `"index"` renders
   `templates/index.html`. Returning `"redirect:/login"` sends the browser to
   another URL instead.

### Templates (Thymeleaf)

Templates are plain HTML with `th:` attributes that read from the model:

```html
<p>Welcome back, <span th:text="${currentUser.displayName}">user</span>.</p>

<tr th:each="user : ${users}">
    <td th:text="${user.username}">username</td>
    <td th:text="${user.email}">email</td>
</tr>
```

`th:text` escapes HTML automatically, so user input can't inject markup.
Shared pieces (the page head, the navigation bar, the footer) live in
`templates/fragments/layout.html` and are included with `th:replace`.

### Forms, validation, and Post/Redirect/Get

`RegisterUserController` shows the full form pattern:

- `GET /register` puts an empty `RegistrationForm` in the model and shows the form.
- `POST /register` receives the submitted form. `@Valid` runs the validation
  annotations on `RegistrationForm` (`@NotBlank`, `@Email`, `@Size`), and
  `BindingResult` holds any errors.
- With errors, it re-renders the form, and each error appears next to its field.
- On success, it returns `redirect:/check-email`, so refreshing the page can't
  submit the form twice. This is called **Post/Redirect/Get**.

The form object is a separate class from the entity, so a request can never
set fields like `id` or `verifiedAt`.

### Security in one paragraph

`security/SecurityConfig.java` decides which URLs are public (`PUBLIC_PATHS`)
and requires sign-in for everything else. Spring Security handles
`POST /login` and `POST /logout` itself, so there is no controller method for
them. After signing out, the browser is redirected to `GET /logout`, which
`LogoutController` renders as the signed-out page. Every POST form needs a
**CSRF token**; `th:action="@{...}"` adds it to
the form automatically, which is why forms use `th:action` rather than a plain
`action`.

Errors (exceptions, 404s, security failures) are forwarded to `/error`, which
`AppErrorController` renders with `templates/error.html` instead of Spring
Boot's Whitelabel page. With `app.show-stack-traces: true` the page shows the
exception, message and full stack trace; turn it off in production, since it
exposes internals.

---

## Postgres connectivity

### How the app finds the database

Look in `src/main/resources/application.yaml` and you will **not** find a
database URL, username or password. Here is how the app connects anyway:

1. On startup, `spring-boot-docker-compose` finds `compose.yaml` and runs
   `docker compose up`.
2. It sees a service using the `postgres` image and reads its port and
   `POSTGRES_*` environment variables.
3. It configures the `DataSource` from those values automatically. This is
   called a **service connection**.

On shutdown it runs `docker compose stop`, which stops the containers but
**keeps your data** in the `db` volume.

To point at a different database (for example, on a server), set the usual
Spring properties and they take priority:

```sh
SPRING_DATASOURCE_URL=jdbc:postgresql://db.example.test:5432/app \
SPRING_DATASOURCE_USERNAME=app \
SPRING_DATASOURCE_PASSWORD=change-me \
./mvnw spring-boot:run
```

### Connecting with a database tool

While the app (or `docker compose up`) is running, Postgres listens on
`localhost:5432`:

| Setting | Value |
| --- | --- |
| Host | `localhost` |
| Port | `5432` |
| Database | `postgres` |
| User | `postgres` |
| Password | `postgres` |

These credentials are for **local development only**.

Or use `psql` inside the container, without installing anything:

```sh
docker compose exec db psql -U postgres
```

Useful `psql` commands:

```sql
\dt                       -- list tables
\d users                  -- describe the users table
SELECT id, username, email, verified_at FROM users;
SELECT * FROM flyway_schema_history;   -- which migrations have run
\q                        -- quit
```

---

## smtp-tester: testing email locally

Registration sends a confirmation email. During development you don't want to
send real email, so `compose.yaml` runs
[smtp-tester](https://github.com/sparrowlogic/smtp-tester), a **local mail
sink**: it accepts every email over SMTP and **keeps it** instead of
delivering it.

| Port | Purpose |
| --- | --- |
| `1025` | SMTP: the app sends mail here (`spring.mail.host` / `spring.mail.port` in `application.yaml`) |
| `8025` | Web inbox at <http://localhost:8025>, plus a REST API |

Open <http://localhost:8025> to read every message the app has sent, including
the confirmation link.

smtp-tester also **checks each email against the email standards** (RFC 5321,
RFC 5322, MIME). Because `compose.yaml` sets
`SMTP_TESTER_VALIDATION_REJECT_ON: ERROR`, a malformed email is *rejected*,
and the send fails loudly instead of the bug going unnoticed. Each message's
validation report appears in the inbox UI.

How the email flow is put together:

- `services/ConfirmationMailer.java` builds the message (a plain-text part,
  plus an HTML part from `templates/email/confirm-email.html`) and sends it
  through Spring's `JavaMailSender`.
- `services/EmailVerificationService.java` creates the one-time token,
  stores only its hash, and marks the user verified when the link is used.

The REST API is how the integration tests read the email back and pull out the
confirmation link (see `src/test/java/.../SmtpTesterInbox.java`):

```sh
curl 'http://localhost:8025/api/v1/messages'           # newest first
curl 'http://localhost:8025/api/v1/messages/<id>'      # one message, with text and HTML bodies
curl -X DELETE 'http://localhost:8025/api/v1/messages' # empty the inbox
```

> smtp-tester accepts mail from anyone who can reach it and its inbox has no
> password. That is why `compose.yaml` binds its ports to `127.0.0.1` only.

---

## Docker Compose commands

`compose.yaml` defines two services: **`db`** (Postgres) and **`smtp-tester`**.
`./mvnw spring-boot:run` starts them for you, but you can also manage them
directly. Run these from the project folder:

| Command | What it does |
| --- | --- |
| `docker compose up -d` | Start both services in the background |
| `docker compose up -d db` | Start only Postgres |
| `docker compose ps` | Show which services are running, and their ports |
| `docker compose logs -f` | Follow the logs of all services (Ctrl+C to stop following) |
| `docker compose logs -f smtp-tester` | Follow one service's logs |
| `docker compose exec db psql -U postgres` | Open a SQL prompt in the database |
| `docker compose stop` | Stop the services; **data is kept** |
| `docker compose down` | Stop and remove the containers; **data is kept** in the volumes |
| `docker compose down -v` | Remove containers **and volumes**: deletes all database rows and captured mail |
| `docker compose pull` | Download newer versions of the images |

**Starting over with an empty database:**

```sh
docker compose down -v
./mvnw spring-boot:run     # Flyway recreates the tables from the migrations
```

`down -v` permanently deletes your local data. That's usually what you want
when experimenting, but only ever run it against your own local setup.

---

## Tests and quality gates

```sh
make test               # unit tests only, no Docker needed (fast)
make agent-completion   # the full gate: run this before you commit
```

There are two kinds of test:

- **Unit tests** (`*Tests.java`) test plain Java logic. No database, no Spring
  context, and they run in milliseconds.
- **Integration tests** (`*IT.java`, tagged `@Tag("integration")`) start the
  whole app with **Testcontainers**: a throwaway Postgres container and a
  throwaway smtp-tester container, created for the test run and deleted
  afterwards. They drive the real security filter chain, real SQL and real
  email delivery. `AuthenticationFlowIT` and `EmailConfirmationFlowIT` are good
  examples to read. These need Docker running.

`make agent-completion` runs every check in order and stops at the first
failure:

1. **Spotless** formats the code consistently.
2. **Checkstyle** enforces style and design rules; any warning fails the build.
3. **Compile with NullAway** fails the build if a value that might be `null`
   is used as if it can't be. Mark nullable things with `@Nullable`.
4. **All tests, plus JaCoCo:** at least **60%** of lines must be covered by tests.
5. **Suppressions guard:** `checkstyle-suppressions.xml` must stay empty. Fix
   warnings rather than silencing them.

For a quick compile while you're mid-change, `./mvnw -Pdev compile` skips the
checks. Don't treat that as "done": run `make agent-completion` before you
commit. Run `make help` to see all targets.

---

## Project layout

```
compose.yaml                     Postgres + smtp-tester for local development
pom.xml                          dependencies and build/quality-gate plugins
Makefile                         make test / make agent-completion / make help
src/main/java/edu/northwestu/project1p2/
  Project1p2Application.java     entry point (main method)
  config/                        AppProperties (app.* settings), Clock bean
  controllers/                   HTTP endpoints (Index, Login, Register, EmailVerification)
  entities/                      User, EmailVerificationToken (one row = one object)
  forms/                         RegistrationForm (what the register form submits)
  repositories/                  UserRepository, EmailVerificationTokenRepository
  security/                      SecurityConfig, the signed-in user (AppUserDetails)
  services/                      business logic (registration, search, email confirmation)
src/main/resources/
  application.yaml               configuration (mail server, app.base-url, ...)
  db/migration/                  Flyway SQL migrations (V1__..., V2__...)
  templates/                     Thymeleaf pages; fragments/layout.html is shared chrome
  templates/email/               email bodies
  static/                        CSS and JavaScript
src/test/java/...                unit tests (*Tests) and integration tests (*IT)
```

### Adding a feature: a typical path

1. **Schema:** add `V3__....sql` in `db/migration/`.
2. **Entity:** add or extend a class in `entities/`.
3. **Repository:** add an interface in `repositories/`, or new query methods.
4. **Service:** put the rules in a class in `services/`.
5. **Controller + template:** add the endpoint and the page.
6. **Tests:** a unit test for the logic, an integration test for the flow.
7. **Gate:** `make agent-completion`, then commit.


## demo of git pull / branching / PR flow.

## Teacher 
- jackdpeterson

## Students working on this project
- Brett
- Geovana 
- James
- Trevor
