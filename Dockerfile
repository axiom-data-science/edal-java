FROM maven:3.6.3-jdk-11 as builder
MAINTAINER Jesse Lopez <jesse@axiomdatascience.com>

# Copy app
WORKDIR /usr/src/app
COPY . /usr/src/app/

CMD ["mvn", "clean", "test", "install"]

