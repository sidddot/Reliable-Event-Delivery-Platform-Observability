.PHONY: up down test test-unit build logs clean e2e load chaos

up:
	docker compose up --build -d

down:
	docker compose down -v

test:
	cd platform && mvn clean test

test-unit:
	cd platform && mvn test

build:
	docker compose build

logs:
	docker compose logs -f

clean:
	cd platform && mvn clean
	docker compose down -v --remove-orphans

e2e:
	pytest tools/e2e -v

load:
	python tools/load_test/run_load.py

chaos:
	python tools/chaos/chaos_runner.py
