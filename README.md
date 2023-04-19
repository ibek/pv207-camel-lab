## PV207 - Camel Lab

for beginners

without the need for programming skills

CREATE TABLE users (
   id INTEGER PRIMARY KEY,
   name TEXT NOT NULL,
   UNIQUE("name")
);

curl -X POST http://0.0.0.0:8080/hello -H "Content-Type: text/plain" -d "Ivo"

curl -X POST http://0.0.0.0:8080/hijson -H "Content-Type: application/json" -d "{\"name\":\"Ivo\"}"

curl -X POST http://0.0.0.0:8080/users -H "Content-Type: application/json" -d "{\"name\":\"Ivo\"}"

curl http://0.0.0.0:8080/users/Ivo

curl -X POST http://0.0.0.0:8080/filteredusers -H "Content-Type: application/json" -d "{\"name\":\"John-bot\"}"

curl -X POST http://0.0.0.0:8080/order -H "Content-Type: application/json" -d "{\"total\":50}"
