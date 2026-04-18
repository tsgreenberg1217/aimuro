#!/bin/zsh
echo "🧑‍🚀🧑‍🚀🧑‍🚀🧑‍🚀 BUILDING AIMURO SERVICE 🧑‍🚀🧑‍🚀🧑‍🚀🧑‍🚀"
name="aimuro-service"
./gradlew build
docker build -t $name .