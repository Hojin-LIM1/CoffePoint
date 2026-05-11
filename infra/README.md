# ☕ 커피포인트 v2 — AWS 인프라 가이드

## 아키텍처 개요

```
                        ┌─────────────┐
                        │   Route 53  │
                        └──────┬──────┘
                               │
                        ┌──────▼──────┐
                        │     ALB     │  ← Health Check: /actuator/health
                        └──────┬──────┘
                               │
              ┌────────────────┼────────────────┐
              │                │                │
        ┌─────▼─────┐   ┌─────▼─────┐   ┌─────▼─────┐
        │  ECS Task  │   │  ECS Task  │   │  ECS Task  │  ← 멀티 인스턴스
        │ (Fargate)  │   │ (Fargate)  │   │ (Fargate)  │     desiredCount: 3
        └─────┬──┬───┘   └─────┬──┬───┘   └─────┬──┬───┘
              │  │              │  │              │  │
    ┌─────────▼──▼──────────────▼──▼──────────────▼──▼─────────┐
    │                                                          │
    │  ┌──────────┐    ┌──────────────┐    ┌────────────────┐  │
    │  │ RDS MySQL│    │ ElastiCache  │    │   MSK (Kafka)  │  │
    │  │ (Single  │    │   (Redis)    │    │                │  │
    │  │  Writer) │    │              │    │                │  │
    │  └──────────┘    └──────────────┘    └───────┬────────┘  │
    │                                             │            │
    │                       Private Subnet (VPC)  │            │
    └─────────────────────────────────────────────┘            │
                                                               │
                                              ┌────────────────▼──┐
                                              │  Analytics Consumer│
                                              │  (ECS 별도 서비스)  │
                                              └───────────────────┘
```

## 멀티 인스턴스에서 안전한 이유

| 관심사 | 전략 | 멀티 인스턴스 안전 근거 |
|--------|------|----------------------|
| 포인트 정합성 | DB 낙관적 락 (@Version) | RDS가 Single Source of Truth |
| 재고 정합성 | DB 비관적 락 (SELECT FOR UPDATE) | RDS 행 잠금 → 인스턴스 무관 |
| 인기 메뉴 캐시 | ElastiCache Redis | 공유 캐시 → 모든 인스턴스 동일 결과 |
| 이벤트 전달 | Transactional Outbox + MSK | DB 트랜잭션으로 유실 방지 |
| 세션 | Stateless | 인스턴스 간 상태 공유 불필요 |

## ⚠️ Outbox 스케줄러 중복 실행 주의

멀티 인스턴스에서 `OutboxScheduler`가 모든 인스턴스에서 동시에 실행되면
같은 이벤트를 중복 전송할 수 있습니다.

**해결 방법 (택 1):**

1. **ShedLock** (추천) — DB 기반 분산 스케줄러 락
```java
@SchedulerLock(name = "outboxScheduler", lockAtMostFor = "PT30S")
@Scheduled(fixedRateString = "${outbox.scheduler.rate}")
public void processOutbox() { ... }
```

2. **별도 서비스 분리** — Outbox 스케줄러를 별도 ECS 서비스(1인스턴스)로 분리

3. **Kafka Consumer 전용 인스턴스** — Consumer와 Outbox를 합쳐서 별도 운영

## AWS 리소스 생성 순서

### 1. VPC + 서브넷
```bash
aws ec2 create-vpc --cidr-block 10.0.0.0/16
# Public Subnet (ALB용)
# Private Subnet (ECS, RDS, ElastiCache, MSK용)
```

### 2. RDS MySQL
```bash
aws rds create-db-instance \
  --db-instance-identifier coffee-point-db \
  --engine mysql \
  --engine-version 8.0 \
  --db-instance-class db.t3.micro \
  --master-username coffee \
  --master-user-password <password> \
  --allocated-storage 20 \
  --vpc-security-group-ids <sg-id>
```

### 3. ElastiCache Redis
```bash
aws elasticache create-cache-cluster \
  --cache-cluster-id coffee-point-redis \
  --engine redis \
  --cache-node-type cache.t3.micro \
  --num-cache-nodes 1
```

### 4. MSK (Kafka)
```bash
aws kafka create-cluster \
  --cluster-name coffee-point-kafka \
  --broker-node-group-info instanceType=kafka.t3.small,clientSubnets=<subnet-ids> \
  --number-of-broker-nodes 3
```

### 5. ECR
```bash
aws ecr create-repository --repository-name coffee-point
```

### 6. ECS Cluster + Service
```bash
# 클러스터
aws ecs create-cluster --cluster-name coffee-point-cluster

# 태스크 정의 등록
aws ecs register-task-definition --cli-input-json file://infra/ecs/task-definition.json

# 서비스 생성 (멀티 인스턴스: desiredCount=3)
aws ecs create-service \
  --cluster coffee-point-cluster \
  --service-name coffee-point-service \
  --task-definition coffee-point-task \
  --desired-count 3 \
  --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[<subnet-ids>],securityGroups=[<sg-id>]}" \
  --load-balancers "targetGroupArn=<tg-arn>,containerName=coffee-point,containerPort=8080" \
  --deployment-configuration "maximumPercent=200,minimumHealthyPercent=50"
```

### 7. Parameter Store (Secrets)
```bash
aws ssm put-parameter --name "/coffee-point/prod/db-url" \
  --value "jdbc:mysql://<rds-endpoint>:3306/coffee_point" --type SecureString

aws ssm put-parameter --name "/coffee-point/prod/db-username" \
  --value "coffee" --type SecureString

aws ssm put-parameter --name "/coffee-point/prod/db-password" \
  --value "<password>" --type SecureString

aws ssm put-parameter --name "/coffee-point/prod/redis-host" \
  --value "<elasticache-endpoint>" --type SecureString

aws ssm put-parameter --name "/coffee-point/prod/kafka-brokers" \
  --value "<msk-bootstrap-servers>" --type SecureString
```

## GitHub Secrets 설정

| Secret Name | 값 |
|-------------|-----|
| `AWS_ACCESS_KEY_ID` | IAM Access Key |
| `AWS_SECRET_ACCESS_KEY` | IAM Secret Key |
| `AWS_REGION` | `ap-northeast-2` |
| `AWS_ACCOUNT_ID` | AWS 계정 ID |
| `ECR_REPOSITORY` | `coffee-point` |
| `ECS_CLUSTER` | `coffee-point-cluster` |
| `ECS_SERVICE` | `coffee-point-service` |
| `ECS_TASK_DEFINITION` | `coffee-point-task` |

## 배포 흐름

```
1. 개발자가 feature 브랜치에서 작업
2. PR 생성 → GitHub Actions 테스트 자동 실행
3. 코드 리뷰 + 테스트 통과 → main에 merge
4. main push → Docker Build → ECR Push → ECS 롤링 배포
5. ECS가 새 태스크 3개 시작 → ALB 헬스체크 통과 → 구 태스크 종료
6. 무중단 배포 완료 (minimumHealthyPercent: 50%)
```
