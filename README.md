# Flashbot

Flashbot is an open-source program for building, testing, and deploying Cryptocurrency trading strategies. Here are some of the main featues:

* Collect market data from supported exchanges (Coinbase, Binance, more to come)
* Backtest strategies on past data (trading fees accurately calculated)
* Query market data and analyze strategy performance with Python/Jupyter
* Support for additional data sources (for example, Twitter volume/sentiment analysis)
* High-ish frequency trading
  * Strategies have access to the full order book
  * Replay the order book to any point in time to debug your trades
  * Simulate latency during backtesting

TODO
* Manage bots through a web UI
* Walk-forward and others strategy auto-optimization

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
```groovy
dependencies {
    compile 'com.github.flashbook:flashbot:0.1.1'
}
```

#### SBT
Add the dependency to build.sbt
```groovy
libraryDependencies += "com.github.flashbook" % "flashbot" % "0.1.1"
```


