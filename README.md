# Flashbot

Flashbot is an open-source program for building, testing, and deploying Cryptocurrency trading strategies. Here are some of the main featues:

* Collect market data from supported exchanges
* Develop strategies based on powerful indicators
  * MACD, EMA, SMA, RSI, Simple Linear Regression, Covariance, and over 120 more
* Backtest strategies on past data (trading fees accurately calculated)
* Query market data and analyze performance with Python/Jupyter
* Support for additional data sources (such as Twitter volume/sentiment analysis)
* Manage bots through a web UI

You can also get the following additional features with Flashbot Pro:

* High frequency trading
  * Strategies have access to the full order book
  * Replay the order book to any point in time to debug your trades
  * Simulate latency during backtesting
* Walk-forward strategy auto-optimization
* Optionally deploy strategies through our cloud service Flashbook.io

## Install
To run Flashbot, download the latest `flashbot.jar` from the [releases](https://github.com/flashbook/flashbot/releases) page.

Get started by trying some of the [tutorials](https://github.com/flashbook/flashbot#tutorials) or run with the `--help` option to see usage info.
```bash
$ java -jar flashbot.jar --help
```

## Ops
### Grafana
### Logs
### Miner

## Java Client Library

#### Maven

Add the dependency to pom.xml
```xml
<dependency>
    <groupId>com.github.flashbook</groupId>
    <artifactId>flashbot</artifactId>
    <version>0.1.1</version>
</dependency>
```

#### Gradle
Add the dependency to build.gradle
```
dependencies {
    compile 'com.github.flashbook:flashbot:0.1.1'
}
```

#### SBT
Add the dependency to build.sbt
```
libraryDependencies += "com.github.flashbook" % "flashbot" % "0.1.1"
```


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
