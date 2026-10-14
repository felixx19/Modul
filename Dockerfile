FROM eclipse-temurin:21-jdk

WORKDIR /app
COPY server.java /app/server.java
COPY Source/Mentah/ /app/initial-materials/
RUN javac -Xlint:all -d /app/out /app/server.java

ENV MATERIALS_DIR=/var/data/materials
ENV INITIAL_MATERIALS_DIR=/app/initial-materials
EXPOSE 8080

CMD ["java", "-cp", "/app/out", "server"]
