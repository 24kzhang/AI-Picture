# 复制本文件为 runtime-data/start-env.ps1，再填写本机连接信息。
# runtime-data 目录已被 Git 忽略，不会推送到远程仓库。
$env:DB_URL = 'jdbc:mysql://localhost:3306/cloud_gallery?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false'
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = '请填写本机数据库密码'
$env:REDIS_HOST = '127.0.0.1'
$env:REDIS_PORT = '6379'
$env:REDIS_DATABASE = '1'
$env:REDIS_PASSWORD = '请填写本机 Redis 密码'
