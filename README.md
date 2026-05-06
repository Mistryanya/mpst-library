# How to run mpst-library within the microservice architecture


## Introduction

Each sub group inside of the group **dissertation-mpst-project** has a different microservice architecture
1. banking-mircroservices
2. example-mircroservices

Both of these microservice architectures have the mpst-library imported via build.gradle dependencies as:

```
implementation files('../mpst-library/build/libs/mpst-library-0.0.1-SNAPSHOT-plain.jar')
```

## **PLEASE NOTE:** 
The default protocol loaded into the library is for the banking-mircroservices, to change for the testing services you MUST update ProtocolInitializer.java variable:

```java
@Value("${protocolPath:protocol-examples/banking_protocol.json}")
private String protocolPath;
```

to be 

```java
@Value("${protocolPath:protocol-examples/example_protocol.json}")
private String protocolPath;
```


## Prerequisits

* Java 17
* Spring Boot Framework 4.0.3
* **Redis**

### How to set up Redis

In order for the mspt-library to load the protocol you need to have a Redis server running. 

* **STEP 1:** [Download Redis (https://redis.io/docs/latest/operate/oss_and_stack/install/archive/install-redis/)]
* **STEP 2:** Make sure Redis server is running on port **6379**

### **Redis NOTE:** 

Redis needs to be cleared in order to restart the protocol and when you swicth the .json file being used 

```ubuntu
redis-cli FLUSHALL
```



## How to run a microservice architecture WITH the mpst-library locally

* **STEP 1:** Clone all of the repos locally for the architecture you want to run 
* **STEP 2:** Clone mpst-library 
* **STEP 3:** Run in mpst-library:

bash
``` bash
./gradlew clean build -x test
```

To build the library project and produce a JAR in build/libs/. The application project then uses that JAR as a local dependency (shown above).

* **STEP 4:** Run all microservices **(check each services README.md file for instructions for how to run each service)**
* **STEP 5:** Test APIs via either swagger or postman (I will detail some examples so requests are easy to run)
* **STEP 6:** Check logging to see if the monitor is running correctly

## How to make requests for banking-mircroservices (custom postman docs)

```
https://documenter.getpostman.com/view/53865392/2sBXqKqLo1
```

