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


