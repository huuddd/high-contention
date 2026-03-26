# ============================================================================
# High Contention Ticketing Service — Makefile
# ============================================================================
# Usage:
#   make db-up          Start PostgreSQL 16 + Redis 7
#   make db-down        Stop containers
#   make db-reset       Stop, remove volumes, restart fresh
#   make migrate        Run Flyway migrations
#   make seed           Insert test data
#   make app-run        Start Spring Boot application
#   make bench:naive    Run k6 benchmark for naive strategy
#   make bench:pessimistic  Run k6 benchmark for pessimistic locking
#   make bench:occ      Run k6 benchmark for OCC
#   make bench:serializable Run k6 benchmark for serializable
#   make bench:reservation  Run k6 benchmark for reservation+fencing
#   make bench:queue    Run k6 benchmark for queue-based
# ============================================================================

.PHONY: db-up db-down db-reset db-logs migrate seed app-run \
        bench\:naive bench\:pessimistic bench\:occ \
        bench\:serializable bench\:reservation bench\:queue \
        clean help

# --- Configuration -----------------------------------------------------------
COMPOSE       := docker compose
FLYWAY        := cd source/ticketing-service && mvn flyway:migrate
SPRING_RUN    := cd source/ticketing-service && mvn spring-boot:run
K6            := k6 run
BASE_URL      ?= http://localhost:8080
LOAD_TEST_DIR := load-test

# --- Infrastructure ----------------------------------------------------------

db-up: ## Start PostgreSQL + Redis containers
	$(COMPOSE) up -d postgres redis
	@echo "⏳ Waiting for PostgreSQL to be healthy..."
	@$(COMPOSE) exec postgres sh -c 'until pg_isready -U ticketing; do sleep 1; done'
	@echo "✅ PostgreSQL + Redis are up and healthy"

db-down: ## Stop all containers
	$(COMPOSE) down
	@echo "✅ Containers stopped"

db-reset: ## Stop containers, remove volumes, restart fresh
	$(COMPOSE) down -v
	$(COMPOSE) up -d postgres redis
	@echo "⏳ Waiting for PostgreSQL to be healthy..."
	@$(COMPOSE) exec postgres sh -c 'until pg_isready -U ticketing; do sleep 1; done'
	@echo "✅ Database reset complete — volumes removed, fresh start"

db-logs: ## Tail container logs
	$(COMPOSE) logs -f postgres redis

# --- Database ----------------------------------------------------------------

migrate: ## Run Flyway migrations
	$(FLYWAY)
	@echo "✅ Migrations applied"

seed: ## Insert test seed data
	$(COMPOSE) exec -T postgres psql -U ticketing -d ticketing -f /dev/stdin < db/seeds/seed_events.sql
	@echo "✅ Seed data inserted"

# --- Application -------------------------------------------------------------

app-run: ## Start Spring Boot application
	$(SPRING_RUN)

# --- Benchmarks (k6) --------------------------------------------------------

bench\:naive: ## Run k6 Stock=1 benchmark for Naive strategy
	$(K6) $(LOAD_TEST_DIR)/stock1.js -e BASE_URL=$(BASE_URL) -e STRATEGY=naive
	@echo "✅ Naive benchmark complete"

bench\:pessimistic: ## Run k6 Stock=1 benchmark for Pessimistic Locking
	$(K6) $(LOAD_TEST_DIR)/stock1.js -e BASE_URL=$(BASE_URL) -e STRATEGY=pessimistic
	@echo "✅ Pessimistic benchmark complete"

bench\:occ: ## Run k6 Stock=1 benchmark for OCC
	$(K6) $(LOAD_TEST_DIR)/stock1.js -e BASE_URL=$(BASE_URL) -e STRATEGY=occ
	@echo "✅ OCC benchmark complete"

bench\:serializable: ## Run k6 Stock=1 benchmark for SERIALIZABLE
	$(K6) $(LOAD_TEST_DIR)/stock1.js -e BASE_URL=$(BASE_URL) -e STRATEGY=serializable
	@echo "✅ Serializable benchmark complete"

bench\:reservation: ## Run k6 Stock=1 benchmark for Reservation+Fencing
	$(K6) $(LOAD_TEST_DIR)/stock1.js -e BASE_URL=$(BASE_URL) -e STRATEGY=reservation
	@echo "✅ Reservation benchmark complete"

bench\:queue: ## Run k6 Stock=1 benchmark for Queue-based
	$(K6) $(LOAD_TEST_DIR)/stock1.js -e BASE_URL=$(BASE_URL) -e STRATEGY=queue
	@echo "✅ Queue benchmark complete"

# --- Utilities ---------------------------------------------------------------

clean: ## Remove build artifacts
	cd source/ticketing-service && mvn clean
	@echo "✅ Build artifacts cleaned"

help: ## Show this help
	@grep -E '^[a-zA-Z_\\:]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-24s\033[0m %s\n", $$1, $$2}'
