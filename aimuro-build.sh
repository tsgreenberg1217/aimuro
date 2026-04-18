#!/bin/zsh
echo "🧑‍🚀🧑‍🚀🧑‍🚀🧑‍🚀 BUILDING AIMURO SERVICE 🧑‍🚀🧑‍🚀🧑‍🚀🧑‍🚀"
name="aimuro-service"
./gradlew build
docker build -t $name .
#docker tag $name tsgreenberg1217/todds-playground:$name
#docker push tsgreenberg1217/todds-playground:$name