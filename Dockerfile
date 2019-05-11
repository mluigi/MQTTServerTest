FROM mysql
ENV MYSQL_DATABASE test
COPY ./sql-scripts/ /docker-entrypoint-initdb.d/
EXPOSE 3306 33060