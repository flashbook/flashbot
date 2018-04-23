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
$ java -jar flashbot.jar ingest --exchanges=gdax --dataDir=/my/data/dir
```

### Start a Flashbot server
In another shell, we'll run the jar with the `server` command and point it at the directory we're ingesting to.
```bash
# Starts a Flashbot server on the default port (9020)
$ java -jar flashbot.jar server --dataDir=/my/data/dir
```

The server processes market data as it's being ingested and provides all kinds of interesting data and aggregations via a GraphQL endpoint.

Visit `http://localhost:9020/graphiql` in a browser to interactively explore and query the Flashbot GraphQL API.

### Connect via the client
Here we'll use the Flashbot Java client library to start exporing our data. If you're using Python, you can skip to the [Python and Jupyter](https://github.com/flashbook/flashbot#python-and-jupyter) section.

1. Start a Flashbot server, if not already running.
    ```bash
    $ java -jar flashbot.jar server --exchanges=gdax --dataDir=/my/data/dir
    ```

2. Install the client library
    
    Follow the [Java Library](https://github.com/flashbook/flashbot#java-library) instructions to get the Java client.

3. Query the Flashbot server for some market data, in this case, we request an aggregated order book and look up the price of the best ask.

    ```java
    import io.flashbook.flashbot.client.Client;
    
    Client client = new Client(9020);
    Double bestAsk = client.orderBook('gdax', 50).asks.get(0).price;
    ```

### Run and analyze a built-in strategy
Flashbot comes with a few sample strategies in `io.flashbook.flashbot.strategies`. Let's run a Moving Average Crossover Strategy, a.k.a. the "Hello World" of algorithmic trading on some historical data.

```java
import io.flashbook.flashbot.server.Server;
import io.flashbook.flashbot.client.Client;

Client fb = new Client(9020);
fb.newBot("io.flashbook.flashbot.strategies.MovingAverageCrossover");
```

### Optimize parameters of built-in strategy

### Custom indicators & ordering logic

### External data sources
Our sample bot is currently looking at only historical order book data to make decisions. i.e. what everyone else is looking at. But what if we want to trade based on, say, streaming Twitter data? Flashbot allows you to setup custom data sources to do exactly this.

1. Extend the `DataIngestService`

## Java Library
- `io.flashbook.flashbot.client.Client` for connecting to a running Flashbot server
- `io.flashbook.flashbot.ingest.IngestService` for ingesting data

#### Maven
```xml
<dependency>
    <groupId>io.flashbook</groupId>
    <artifactId>flashbot</artifactId>
    <version>0.1.1</version>
</dependency>
```

#### Gradle
```
dependencies {
    compile 'io.flashbook:flashbot:0.1.1'
}
```

#### SBT
```
libraryDependencies += "io.flashbook" % "flashbot" % "0.1.1"
```

