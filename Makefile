.PHONY: build test up down logs demo clean

build:
	mvn -B -q clean package

test:
	mvn -B test

up:
	docker compose up --build -d
	@echo "Console: http://localhost:8081  (second gateway on http://localhost:8091)"

down:
	docker compose down -v

logs:
	docker compose logs -f gateway-1 message-service notification-service

# Alice on one gateway, Bob on the other, so a message has to cross nodes to be delivered.
demo:
	@echo "Open http://localhost:8081 and http://localhost:8091 side by side."
	@echo "Connect alice on one, bob on the other, and send."

clean:
	mvn -B -q clean
	docker compose down -v --remove-orphans
