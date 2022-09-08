FROM openjdk:17-oracle
EXPOSE 5000

COPY target/*.jar .
COPY keystore.jks .
CMD java -jar *.jar

