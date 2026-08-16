FROM eclipse-temurin:21-jdk-jammy

WORKDIR /app

COPY src ./src
COPY web ./web

RUN mkdir -p uploads

RUN javac -d out src/main/java/lobby/*.java

ENV PORT=8080
EXPOSE 8080

CMD ["sh", "-c", "java -cp out lobby.Servidor ${PORT}"]
