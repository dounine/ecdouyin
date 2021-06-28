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
打包docker镜像运行
```
sbt clean docker:publishLocal
```
运行
```
docker run -ti -p 8080:40000 dounine/ecdouyin:0.1.0-SNAPSHOT
```
可以使用application.conf使用-e修改对应的配置
