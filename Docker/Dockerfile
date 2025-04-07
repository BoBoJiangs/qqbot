FROM mcr.microsoft.com/playwright/java:v1.32.0-focal

ADD qq-bot-0.0.1-SNAPSHOT.jar /app.jar

EXPOSE 80

CMD ["java","-Djava.security.egd=file:/dev/./urandom","-Dfile.encoding=UTF8","-Duser.timezone=GMT+08","-jar","/app.jar"]