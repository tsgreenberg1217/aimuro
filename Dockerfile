FROM bellsoft/liberica-openjdk-alpine:latest-aarch64
WORKDIR /usr/share/app
COPY build/libs/*.jar app.jar
EXPOSE 8080
CMD ["java", "-jar" ,"app.jar"]