.PHONY: help agent-completion build run test fast format check clean install-hooks

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-18s\033[0m %s\n", $$1, $$2}'

agent-completion: ## FULL gate: format, checkstyle, NullAway, unit + integration tests, coverage >= 80%
	@./scripts/agent-completion.sh

build: ## Full build with every gate (same as `./mvnw clean verify`)
	./mvnw clean verify

run: ## Run locally (starts Postgres via compose.yaml)
	./mvnw spring-boot:run

test: ## Unit tests only (no Docker needed)
	./mvnw test

fast: ## Quick check: spotless:check + checkstyle + compile with NullAway, no tests
	./mvnw -q spotless:check checkstyle:check compile

format: ## Apply spotless formatting
	./mvnw spotless:apply

check: ## spotless:check + checkstyle, no compile or tests
	./mvnw spotless:check checkstyle:check

clean: ## Remove build output
	./mvnw clean

install-hooks: ## Install git hooks: pre-commit runs `make fast`, pre-push runs `make agent-completion`
	@HOOKS_DIR="$$(git rev-parse --git-path hooks)"; \
	mkdir -p "$$HOOKS_DIR"; \
	printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail' 'cd "$$(git rev-parse --show-toplevel)"' 'make fast' > "$$HOOKS_DIR/pre-commit"; \
	printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail' 'cd "$$(git rev-parse --show-toplevel)"' 'make agent-completion' > "$$HOOKS_DIR/pre-push"; \
	chmod +x "$$HOOKS_DIR/pre-commit" "$$HOOKS_DIR/pre-push"; \
	echo "Installed pre-commit (make fast) and pre-push (make agent-completion) into $$HOOKS_DIR"
