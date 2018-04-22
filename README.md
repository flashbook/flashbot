# Flashbot

Flashbot is a program for developing and running high-frequency Cryptocurrency trading systems.

You can connect to it from Java, Python, or any client that speaks GraphQL.

## Install
To run Flashbot, all you need to do is download the latest `flashbot.jar` from the [releases](https://github.com/flashbook/flashbot/releases) page.

Get started by trying some of the [tutorials](https://github.com/flashbook/flashbot#tutorials) or run with the `--help` option to see usage info.
```bash
$ java -jar flashbot.jar
```

### Java Library
Use the Java library to run a Flashbot `Server` instance directly, or to use the Flashbot client library to connect to a (possibly remote) Flashbot server.

#### Maven
1. Add the JitPack repository to pom.xml
    ```xml
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
    ```

2. Add the dependency to pom.xml
    ```xml
    <dependency>
        <groupId>com.github.flashbook</groupId>
        <artifactId>flashbot</artifactId>
        <version>0.1.1</version>
    </dependency>
    ```

#### Gradle
1. Add the JitPack repository to build.gradle
    ```
    allprojects {
        repositories {
            ...
            maven { url 'https://jitpack.io' }
        }
    }
    ```
    
2. Add the dependency to build.gradle
    ```
    dependencies {
        compile 'com.github.flashbook:flashbot:0.1.1'
    }
    ```

#### SBT
1. Add the JitPack repository to the end of resolvers in build.sbt
    ```
    resolvers += "jitpack" at "https://jitpack.io"
    ```
    
2. Add the dependency to build.sbt
    ```
    libraryDependencies += "com.github.flashbook" % "flashbot" % "0.1.1"
    ```

## Tutorials

### Start collecting data
Let's start with the built-in GDAX market data for now. This connects to the GDAX REST and WebSocket APIs and saves order book data to disk in real time. Leave the following command running in a shell.
```bash
$ java -jar flashbot.jar collect --keys=gdax --dataDir=/my/data/dir
```



### Run a sample strategy
Now that we have a streaming source of market data in our `--dataDir` directory, we can run strategies on it. A strategy is identified by a fully qualified Java class name.

```java
import io.flashbook.flashbot.server.Server;
import io.flashbook.flashbot.client.Client;
import io.flashbook.flashbot.client.Config;

// Create an in-memory server for testing
Server server = new Server();
Client client = new Client(server);

client.run()
```
