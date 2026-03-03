.PHONY: build test lint clean assemble help

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'

build: ## Build all modules
	./gradlew build

test: ## Run unit tests
	./gradlew test

lint: ## Run lint checks
	./gradlew lint ktlintCheck

format: ## Format code with ktlint
	./gradlew ktlintFormat

assemble: ## Assemble release AAR
	./gradlew :octomil:assembleRelease

clean: ## Clean build artifacts
	./gradlew clean
