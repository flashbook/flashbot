# Flashbot

Flashbot is a program for developing and running high-frequency Cryptocurrency trading systems. You can connect to it from Java, Python, Jupyter, and any other clients that speaks GraphQL.

## Docs
The [Flashbot Wiki](https://github.com/flashbook/flashbot/wiki) hosts the docs, as well as a series of tutorials you from an empty Flashbot installation to running a profitable strategy live on GDAX.

TODO: Link
[JavaDoc]()

## Install
Flashbot is typically run as two or more processes:
1. Data ingest
2. Trading server

### Executable Jar
To run a Flashbot trading server and a market data ingest service, download the latest `flashbot.jar` from the [releases](https://github.com/flashbook/flashbot/releases) page.

Get started by trying some of the [tutorials](https://github.com/flashbook/flashbot#tutorials) or run with the `--help` option to see usage info.
```bash
$ java -jar flashbot.jar --help
```

Looking for the Java client library, or just want to run the server manually? Jump to the [Java Library](https://github.com/flashbook/flashbot#java-library) section to see how to integrate with the various JVM build tools.

### Java library
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
