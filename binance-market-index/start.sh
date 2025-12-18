#!/bin/bash
echo "Starting Binance Market Index with 1.5G heap memory..."
java -Xms512m -Xmx1536m -jar target/market-index-1.0.0.jar
