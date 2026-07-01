# 비밀이 아닌 값 (커밋 대상). 비밀값은 secrets.auto.tfvars 에 별도로 둔다(gitignore).

region      = "ap-northeast-2"
aws_profile = "sandbox" # ← 새 Sandbox 계정용 프로필명으로 교체 (README 참고)

project_name = "laimory"

vpc_cidr             = "10.0.0.0/16"
azs                  = ["ap-northeast-2a", "ap-northeast-2c"]
public_subnet_cidrs  = ["10.0.0.0/20", "10.0.16.0/20"]
private_subnet_cidrs = ["10.0.32.0/20", "10.0.48.0/20"]

was_instance_types = {
  dev  = "t3.small"
  prod = "t3.micro"
}
mysql_instance_type = "t3.micro"
redis_instance_type = "t3.micro"
ai_instance_type    = "t3.micro"

mysql_private_ip = {
  dev  = "10.0.32.12"
  prod = "10.0.32.10"
}
redis_private_ip = "10.0.32.11"

app_port = 8080

github_repo          = "soma17th-369/Laimory-server"
github_deploy_branch = "dev"

db_app_username    = "laimory"
redis_app_username = "laimory_app"
