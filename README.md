# Flashbot

Flashbot is a program for developing and running high-frequency Cryptocurrency trading systems.

You can connect to it from Java, Python, or any client that speaks GraphQL.

## Install
To run Flashbot, download the latest `flashbot.jar` from the [releases](https://github.com/flashbook/flashbot/releases) page.

Get started by trying some of the [tutorials](https://github.com/flashbook/flashbot#tutorials) or run with the `--help` option to see usage info.
```bash
$ java -jar flashbot.jar --help
```

Looking for the Java client library, or just want to run the server manually? Jump to the [Java Library](https://github.com/flashbook/flashbot#java-library) section to see how to integrate with the various JVM build tools.

## Tutorials

### Ingest market data
Let's start with the built-in GDAX market data for now. This connects to the GDAX REST and WebSocket APIs and saves order book data to disk in real time. Leave the following command running in a shell.
```bash
# Run with --help to see all options
$ java -jar flashbot.jar ingest --exchanges=gdax --dataDir=/my/data/dir
```

### Start a Flashbot server
In another shell, we'll run the jar with the `server` command and point it at the directory we're ingesting to.
```bash
$ java -jar flashbot.jar server --dataDir=/my/data/dir
```

The server processes market data as it's being ingested and provides all kinds of interesting data and aggregations via a GraphQL endpoint.

Visit `http://localhost:9020/graphiql` in a browser to interactively explore and query the Flashbot GraphQL API.

### Connect from Java or Python
We can use the Flashbot Python client library and a Jupyter notebook to start exporing our data.

1. First, run a Flashbot server in a new shell. This is what our Flashbot client will connect to.
    ```bash
    # Starts a Flashbot server on the default port (9020)
    $ java -jar flashbot.jar server --exchanges=gdax --dataDir=/my/data/dir
    ```

2. Install the client library
    a. Java
    Follow the instructions at [Java Library](https://github.com/flashbook/flashbot#java-library)
    
    b. Python
    ```bash
    pip install flashbot
    ```

3. Query the Flashbot server for some market data, in this case, the price of the best ask resting in the order book.

    a. Java
    
    ```java
    import io.flashbook.flashbot.client.Client;
    
    Client client = new Client(9020);
    Double bestAsk = client.orderBook('gdax', 50).asks.get(0).price;
    ```
    
    b. Python
    
    ```python
    from flashbot import Client
 
    client = Client(9020)
    best_ask = client.order_book('gdax', 50).asks.get(0).price
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

## Java Library
- `io.flashbook.flashbot.client.Client` for connecting to a running Flashbot server
- `io.flashbook.flashbot.ingest.IngestService` for ingesting data

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

