@echo off
cd ..
docker stop testsql
docker rm testsql
docker build -t mqtt MQTTServerTest
docker run -d -p 3306:3306 --name testsql -e MYSQL_ROOT_PASSWORD=pass mqtt