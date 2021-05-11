# ecdouyin

## Deploy
首先在mysql创建utf8编码数据库
```shell
CREATE DATABASE `ecdouyin` CHARACTER SET utf8 COLLATE utf8_general_ci;
```
运行docker selenium服务器
```
docker run -ti --rm -v /etc/localtime:/etc/localtime -v /dev/shm:/dev/shm -p 4445:4444 --name ecdouyin dounine/standalone-chrome-ssl:latest
```