# Standalone testing.jar

Run directly from Jenkins:

    java -jar testing.jar

It tests:
    http://18.61.172.40:8080/testapp/hello

It starts a local Selenium Server, launches headless Chrome, opens the URL, and verifies the expected page text.

Requirements on the Jenkins agent:
- Java 11+ (Java 17/21 recommended)
- Google Chrome/Chromium installed
- Network access to the AWS Tomcat URL
- Internet access to download Selenium Server 4.25.0 on first run

Override URL:
    java -DbaseUrl=http://18.61.172.40:8080/testapp/hello -jar testing.jar
