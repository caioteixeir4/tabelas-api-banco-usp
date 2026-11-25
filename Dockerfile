# Imagem de execucao: o .jar e compilado na maquina (mvn -o package -DskipTests) e so roda aqui.
# Base oficial do Java 24, versao fixada (nada de "latest").
FROM eclipse-temurin:24.0.2_12-jre-noble

# Roda com um usuario sem privilegio, nunca como root.
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin kawashi
WORKDIR /app
COPY target/*.jar /app/app.jar
USER 10001

EXPOSE 8080
# Os segredos chegam por variavel de ambiente (env_file no docker-compose) ou pelo Vault (AppRole);
# nenhum fica dentro da imagem.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
